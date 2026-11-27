"""과거 기록과 README 가져오기 (기능 명세 S-3).

repo가 LINKED가 되면 backfill_jobs에 작업이 생긴다 (sweep). worker는 별도 스레드에서 작업을 하나씩 잡아
GitHub API로 커밋(모든 브랜치), PR, 리뷰, 이슈와 README를 읽고 webhook과 같은 규칙(activities.upsert)으로 저장한다.

- 저장하는 것은 webhook과 같다: SHA/번호/리뷰 ID, 로그인·ID, 제목(커밋은 첫 줄), 상태, 시각, URL.
  이메일, 작성자 이름, 커밋 메시지 본문, PR/이슈/리뷰 본문, diff는 저장하지 않는다.
- 기간: BACKFILL_DAYS (0 = 전체 기록, 기본). 목록 하나당 최대 MAX_PAGES쪽(100건씩)만 읽고,
  리뷰는 가장 최근 PR REVIEW_PR_LIMIT개까지만 읽는다.
- 저장소 전체 선택(all)으로 설치한 경우는 활동 기록을 가져오지 않고 README만 가져온다 (SKIPPED, all_repositories).
- README는 작업을 끝낸 뒤 가져온다. 실패하면 readme_failed 로그만 남기고 작업 결과(DONE, SKIPPED)는 그대로다.
  README 행이 없는 LINKED 저장소는 retry_readmes가 저장소마다 1시간에 한 번 다시 가져온다.
- 멱등: UNIQUE(repo_id, kind, ref) upsert. 실패하면 늦춰서 다시 하고, MAX_ATTEMPTS번째면 FAILED.
  FAILED도 하루 뒤 다시 대상이 된다. 저장소 자체가 없을 때(404 등)만 바로 FAILED이고,
  지워진 브랜치·PR을 끈 저장소·이슈를 끈 저장소는 그 부분만 건너뛴다.
- 저장소가 설치에서 빠지면 작업을 SKIPPED(not_linked)로 바꾸고(events.py), 다시 LINKED가 되면 sweep이 처음부터 다시 한다.
  작업 결과는 PENDING인 작업에만 쓰므로 읽는 사이 SKIPPED가 된 작업을 FAILED나 재시도로 덮어쓰지 않는다.
- 포크한 저장소는 포크한 시점 이후 커밋만 가져온다 (원본 프로젝트의 기록이 팀 활동으로 들어오지 않게).
- 상한(MAX_PAGES, REVIEW_PR_LIMIT)에 걸리면 DONE이되 last_error='truncated'로 남긴다 (오래된 쪽이 빠짐).
"""

import time
from datetime import timedelta, timezone
from urllib.parse import quote, urlencode

from . import activities, readme
from .github import GitHubError
from .log import emit
from .message import timestamp

PER_PAGE = 100
MAX_PAGES = 30          # 목록 하나당 최대 3,000건
MAX_BRANCHES = 30       # 기본 브랜치 말고 더 읽는 브랜치 수
REVIEW_PR_LIMIT = 500   # 리뷰를 읽는 PR 수 (가장 최근 것부터)
MAX_ATTEMPTS = 5
RETRY_DELAY = timedelta(minutes=10)
LEASE = timedelta(minutes=15)   # 처리 중 워커가 멈추면 이 시간 뒤 다시 잡는다
PERMANENT = (404, 410, 451)     # 저장소·설치가 없음: 다시 해도 같다
README_RETRY_INTERVAL = timedelta(hours=1)   # README가 없는 저장소에 다시 요청하는 간격
README_RETRY_LIMIT = 20                      # 한 번 반복에 README를 다시 요청하는 저장소 수


def sweep(conn, days):
    """LINKED이고 설치가 ACTIVE인 repo에 작업을 만든다.
    SKIPPED였던 작업(그때는 대상이 아니었음)은 다시 대상이 되면 처음부터 한다. DONE/FAILED는 그대로.
    전체 선택 설치라서 SKIPPED인 작업은 설치가 selected로 바뀌었을 때만 다시 한다."""
    conn.execute('''
        INSERT INTO backfill_jobs (repo_id, since)
        SELECT r.repo_id, CASE WHEN %(days)s > 0 THEN now() - make_interval(days => %(days)s) END
        FROM repos r JOIN installations i ON i.installation_id = r.installation_id
        WHERE r.status = 'LINKED' AND i.status = 'ACTIVE'
        ON CONFLICT (repo_id) DO UPDATE SET
            status = 'PENDING', since = EXCLUDED.since, attempts = 0, next_attempt_at = now(),
            items = NULL, last_error = NULL, requested_at = now(), finished_at = NULL
        WHERE (backfill_jobs.status = 'SKIPPED'
               AND (backfill_jobs.last_error IS DISTINCT FROM 'all_repositories'
                    OR EXISTS (SELECT 1 FROM repos r2 JOIN installations i2 ON i2.installation_id = r2.installation_id
                               WHERE r2.repo_id = backfill_jobs.repo_id AND i2.repo_selection = 'selected')))
           OR (backfill_jobs.status = 'FAILED' AND backfill_jobs.finished_at < now() - interval '1 day')
        ''', {'days': days})


def claim(conn):
    """기한이 된 작업 하나를 잡는다. 잡는 순간 LEASE만큼 미뤄 두어 중간에 멈춰도 나중에 다시 한다."""
    with conn.transaction():
        job = conn.execute('''
            SELECT j.repo_id, j.since, j.attempts, r.full_name, r.status, r.installation_id,
                   i.repo_selection, i.status
            FROM backfill_jobs j
            JOIN repos r ON r.repo_id = j.repo_id
            JOIN installations i ON i.installation_id = r.installation_id
            WHERE j.status = 'PENDING' AND j.next_attempt_at <= now()
            ORDER BY j.requested_at, j.repo_id
            LIMIT 1
            FOR UPDATE OF j SKIP LOCKED''').fetchone()
        if job:
            conn.execute('UPDATE backfill_jobs SET attempts = attempts + 1, next_attempt_at = now() + %s '
                         'WHERE repo_id = %s', (LEASE, job[0]))
    return job


def run_once(connection_factory, github, days, budget_seconds=15, readme_attempts=None):
    """기한이 된 작업을 budget_seconds 동안 처리한다 (작업 하나는 끝까지). 처리한 작업 수를 돌려준다.
    이어서 README가 없는 저장소의 README를 다시 가져온다 (readme_attempts는 반복 사이에 유지할 dict)."""
    if github is None:  # App 키가 없으면 GitHub API를 부를 수 없다
        return 0
    attempts = {} if readme_attempts is None else readme_attempts
    deadline = time.monotonic() + budget_seconds
    count = 0
    with connection_factory() as conn:
        sweep(conn, days)
        while time.monotonic() < deadline:
            job = claim(conn)
            if job is None:
                break
            run_job(conn, github, job)
            attempts[job[0]] = time.monotonic()  # 작업이 README를 방금 요청했다
            count += 1
        retry_readmes(conn, github, attempts)
    return count


def run_job(conn, github, job):
    repo_id, since, attempts, full_name, repo_status, installation_id, selection, installation_status = job
    attempt = attempts + 1
    if repo_status != 'LINKED' or installation_status != 'ACTIVE':
        _finish(conn, repo_id, 'SKIPPED', error='not_linked')
        emit('backfill_skipped', repo_id=repo_id, reason='not_linked')
        return 'SKIPPED'

    started = time.monotonic()
    notes = {}
    try:
        rows = [] if selection == 'all' else fetch(github, installation_id, full_name, since, notes)
    except GitHubError as error:
        reason = f'http_{error.status}' if error.status else 'network'
        if error.status in PERMANENT:
            _finish(conn, repo_id, 'FAILED', error=reason)
            result = 'FAILED'
        else:
            result = _retry(conn, repo_id, attempt, reason)
        emit('backfill_failed', repo_id=repo_id, reason=reason, attempt=attempt, result=result)
        return result

    if selection == 'all':
        # 전체 선택 설치는 관계없는 저장소의 기록까지 들어오지 않도록 README만 가져온다
        _finish(conn, repo_id, 'SKIPPED', error='all_repositories')
        emit('backfill_skipped', repo_id=repo_id, reason='all_repositories')
        _refresh_readme(conn, github, repo_id)
        return 'SKIPPED'

    with conn.transaction():
        # 읽는 동안 repo가 빠졌으면 저장하지 않는다 (webhook 경로와 같은 규칙)
        linked = conn.execute("SELECT 1 FROM repos WHERE repo_id = %s AND status = 'LINKED' FOR SHARE",
                              (repo_id,)).fetchone()
        if not linked:
            _finish(conn, repo_id, 'SKIPPED', error='not_linked')
            return 'SKIPPED'
        activities.upsert(conn, repo_id, rows)
        _finish(conn, repo_id, 'DONE', items=len(rows), error='truncated' if notes.get('truncated') else None)
    emit('backfill_finished', repo_id=repo_id, truncated=bool(notes.get('truncated')), fork=bool(notes.get('fork')),
         commits=sum(row[0] == 'COMMIT' for row in rows), prs=sum(row[0] == 'PR' for row in rows),
         reviews=sum(row[0] == 'REVIEW' for row in rows), issues=sum(row[0] == 'ISSUE' for row in rows),
         seconds=round(time.monotonic() - started, 1))
    _refresh_readme(conn, github, repo_id)
    return 'DONE'


def _refresh_readme(conn, github, repo_id):
    """README는 작업을 끝낸 뒤 가져온다. 실패해도 작업 결과는 바꾸지 않고 retry_readmes가 나중에 다시 한다."""
    try:
        readme.refresh(conn, github, repo_id)
    except GitHubError as error:
        emit('readme_failed', repo_id=repo_id, status=error.status)


def retry_readmes(conn, github, attempts):
    """README 행이 없는 LINKED 저장소의 README를 다시 가져온다. 처리를 기다리는 과거 기록 작업이 있는 저장소는
    그 작업이 가져오므로 뺀다. README가 원래 없는 저장소(404)도 섞여 있으므로 attempts(repo_id -> 마지막 시도
    시각, worker 메모리)로 README_RETRY_INTERVAL마다 한 번만, 한 번에 README_RETRY_LIMIT개까지 요청한다."""
    now = time.monotonic()
    rows = conn.execute('''
        SELECT r.repo_id FROM repos r
        JOIN installations i ON i.installation_id = r.installation_id
        JOIN backfill_jobs j ON j.repo_id = r.repo_id
        WHERE r.status = 'LINKED' AND i.status = 'ACTIVE' AND j.status <> 'PENDING'
          AND NOT EXISTS (SELECT 1 FROM repo_readmes m WHERE m.repo_id = r.repo_id)
        ORDER BY r.repo_id''').fetchall()
    due = [rid for (rid,) in rows
           if rid not in attempts or now - attempts[rid] >= README_RETRY_INTERVAL.total_seconds()]
    for rid in due[:README_RETRY_LIMIT]:
        attempts[rid] = now
        _refresh_readme(conn, github, rid)
    return min(len(due), README_RETRY_LIMIT)


def fetch(github, installation_id, full_name, since, notes=None):
    notes = {} if notes is None else notes
    token = github.installation_token(installation_id)
    repo = '/repos/' + quote(full_name, safe='/')
    info = github.get(repo, token)  # 저장소가 없으면 404 → 바로 FAILED
    commit_since = since
    if isinstance(info, dict):
        if isinstance(info.get('full_name'), str) and info['full_name']:
            repo = '/repos/' + quote(info['full_name'], safe='/')  # 이름이 바뀐 저장소
        if info.get('fork') is True:
            notes['fork'] = True
            try:
                forked = timestamp(info.get('created_at'))
                commit_since = max(since, forked) if since else forked
            except ValueError:
                pass
    commits = _commit_rows(github, token, repo, commit_since, notes)
    pulls = _item_rows(github, token, repo, since, 'PR', notes)
    return (commits + pulls + _review_rows(github, token, repo, since, pulls, notes)
            + _item_rows(github, token, repo, since, 'ISSUE', notes))


def _pages(github, token, path, params, notes=None):
    """목록 API를 한 쪽(100건)씩 읽는다. 한 쪽이 100건보다 적으면 끝 (Link 헤더는 쓰지 않음).
    MAX_PAGES쪽을 다 채우면 notes['truncated'] = True (오래된 쪽이 빠짐)."""
    for page in range(1, MAX_PAGES + 1):
        batch = github.get(f'{path}?{urlencode({**params, "per_page": PER_PAGE, "page": page})}', token)
        if not isinstance(batch, list):
            raise GitHubError('Invalid list')
        yield batch
        if len(batch) < PER_PAGE:
            return
    if notes is not None:
        notes['truncated'] = True


def _commit_rows(github, token, repo, since, notes=None):
    params = {'since': since.astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')} if since else {}
    seen, rows = set(), []

    def take(batch):
        new = 0
        for item in batch:
            row = activities.rest_commit_row(item)
            if row and row[1] not in seen:
                seen.add(row[1])
                rows.append(row)
                new += 1
        return new

    try:
        for batch in _pages(github, token, f'{repo}/commits', params, notes):  # 기본 브랜치
            take(batch)
    except GitHubError as error:
        if error.status == 409:  # 빈 저장소
            return []
        raise
    # 다른 브랜치: 처음 보는 커밋이 하나도 없는 쪽이 나오면 그 브랜치는 그만 읽는다 (공통 기록)
    try:
        branches = github.get(f'{repo}/branches?{urlencode({"per_page": PER_PAGE})}', token)
    except GitHubError as error:
        if error.status != 404:
            raise
        branches = []
    names = [b.get('name') for b in branches if isinstance(b, dict)] if isinstance(branches, list) else []
    for name in [n for n in names if isinstance(n, str) and n][:MAX_BRANCHES]:
        try:
            for batch in _pages(github, token, f'{repo}/commits', {**params, 'sha': name}):
                if take(batch) == 0:
                    break
        except GitHubError as error:
            if error.status not in (404, 409, 422):  # 읽는 사이 지워진 브랜치 등은 건너뛴다
                raise
    return rows


def _created(item):
    try:
        return timestamp(item.get('created_at'))
    except ValueError:
        return None


def _item_rows(github, token, repo, since, kind, notes=None):
    path = f'{repo}/pulls' if kind == 'PR' else f'{repo}/issues'
    rows = []
    try:
        for batch in _pages(github, token, path, {'state': 'all', 'sort': 'created', 'direction': 'desc'}, notes):
            for item in batch:
                if not isinstance(item, dict):
                    continue
                created = _created(item)
                if since and created and created < since:
                    return rows  # 만든 시각 역순이라 이 뒤는 모두 기간 밖
                if kind == 'ISSUE' and 'pull_request' in item:
                    continue  # issues 목록에는 PR도 섞여 나온다
                try:
                    rows.append(activities.item_row(kind, item))
                except (KeyError, TypeError, ValueError):
                    continue
    except GitHubError as error:
        if error.status in (404, 410):  # PR이나 이슈를 끈 저장소 (저장소 자체는 위에서 확인함)
            return rows
        raise
    return rows


def _review_rows(github, token, repo, since, pulls, notes=None):
    """PR 목록(만든 시각 역순)의 앞에서 REVIEW_PR_LIMIT개까지 리뷰를 읽는다."""
    if len(pulls) > REVIEW_PR_LIMIT and notes is not None:
        notes['truncated'] = True
    rows = []
    for pull in pulls[:REVIEW_PR_LIMIT]:
        number = pull[1]
        try:
            for batch in _pages(github, token, f'{repo}/pulls/{number}/reviews', {}, notes):
                for review in batch:
                    row = activities.rest_review_row(review, number)
                    if row and not (since and row[6] < since):
                        rows.append(row)
        except GitHubError as error:
            if error.status not in (404, 410):
                raise
    return rows


def _finish(conn, repo_id, status, items=None, error=None):
    # 읽는 사이 저장소가 빠져 SKIPPED가 된 작업(events.py)은 덮어쓰지 않는다
    conn.execute('''UPDATE backfill_jobs SET status = %s, items = %s, last_error = %s, finished_at = now()
                    WHERE repo_id = %s AND status = 'PENDING' ''', (status, items, error, repo_id))


def _retry(conn, repo_id, attempt, reason):
    if attempt >= MAX_ATTEMPTS:
        _finish(conn, repo_id, 'FAILED', error=reason)
        return 'FAILED'
    conn.execute('''UPDATE backfill_jobs SET next_attempt_at = now() + %s, last_error = %s
                    WHERE repo_id = %s AND status = 'PENDING' ''', (RETRY_DELAY * attempt, reason, repo_id))
    return 'RETRY'

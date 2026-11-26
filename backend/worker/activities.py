"""push / pull_request / pull_request_review / issues → activities (명세 6-1 P0, 7-2).

저장하는 것: 커밋 SHA, PR/이슈 번호, 리뷰 ID와 소속 PR 번호, GitHub 로그인/ID, 제목(커밋은 첫 줄),
상태, 시각, PR 병합 시각, URL.
저장하지 않는 것: 이메일, 커밋 메시지 본문, PR/이슈/리뷰 본문, diff (명세 9장).
LINKED가 아닌 repo의 이벤트는 저장하지 않는다.
"""

from .message import timestamp

TITLE_MAX = 300


def _id(value):
    return value if type(value) is int and value > 0 else None


def _first_line(message):
    if not isinstance(message, str):
        return None
    return message.strip().splitlines()[0][:TITLE_MAX] if message.strip() else None


def _title(value):
    return value[:TITLE_MAX] if isinstance(value, str) else None


def repo_id(payload):
    repo = payload.get('repository')
    return _id(repo.get('id')) if isinstance(repo, dict) else None


def commit_rows(payload):
    """push의 각 커밋. 태그 push, 브랜치 삭제는 활동이 아니다."""
    if not str(payload.get('ref', '')).startswith('refs/heads/') or payload.get('deleted'):
        return []
    sender = payload.get('sender') or {}
    rows = []
    for commit in payload.get('commits') or []:
        sha = commit.get('id')
        if not isinstance(sha, str) or not sha:
            continue
        author = commit.get('author') or {}
        login = author.get('username') if isinstance(author.get('username'), str) else None
        # push payload의 커밋 작성자에는 GitHub ID가 없다. push한 사람과 같을 때만 sender ID를 쓴다.
        github_id = _id(sender.get('id')) if login and login == sender.get('login') else None
        rows.append(('COMMIT', sha, github_id, login, _first_line(commit.get('message')), None,
                     timestamp(commit['timestamp']), commit.get('url'), None, None))
    return rows


def rest_commit_row(item):
    """REST /repos/{repo}/commits 목록의 커밋 하나 (backfill). 작성자 이메일·이름은 쓰지 않는다."""
    sha = item.get('sha') if isinstance(item, dict) else None
    commit = item.get('commit') if isinstance(item, dict) else None
    if not isinstance(sha, str) or not sha or not isinstance(commit, dict):
        return None
    when = (commit.get('author') or {}).get('date') or (commit.get('committer') or {}).get('date')
    if not isinstance(when, str):
        return None
    # item.author는 GitHub 계정과 연결된 작성자만 있다 (이메일이 계정에 없으면 null)
    user = item.get('author') if isinstance(item.get('author'), dict) else {}
    return ('COMMIT', sha, _id(user.get('id')),
            user.get('login') if isinstance(user.get('login'), str) else None,
            _first_line(commit.get('message')), None, timestamp(when), item.get('html_url'), None, None)


def item_row(kind, item):
    """PR/이슈 하나. occurred_at은 생성 시각 (주간 통계 기준)."""
    user = item.get('user') or {}
    merged_at = None
    if kind == 'PR':
        state = 'merged' if item.get('merged') or item.get('merged_at') else item.get('state')
        merged_at = timestamp(item['merged_at']) if item.get('merged_at') else None
    else:
        state = item.get('state')
    return (kind, str(item['number']), _id(user.get('id')),
            user.get('login') if isinstance(user.get('login'), str) else None,
            _title(item.get('title')), state, timestamp(item['created_at']), item.get('html_url'),
            None, merged_at)


def review_row(review, pull_request):
    """리뷰 하나. ref는 리뷰 ID, parent_ref는 소속 PR 번호, occurred_at은 제출 시각.
    상태는 소문자로 저장하고 제출 전(pending) 리뷰는 저장하지 않는다."""
    state = review.get('state').lower() if isinstance(review.get('state'), str) else None
    if state == 'pending':
        return None
    user = review.get('user') or {}
    return ('REVIEW', str(review['id']), _id(user.get('id')),
            user.get('login') if isinstance(user.get('login'), str) else None,
            None, state, timestamp(review.get('submitted_at')), review.get('html_url'),
            str(pull_request['number']), None)


def rest_review_row(review, number):
    """REST /repos/{repo}/pulls/{number}/reviews 목록의 리뷰 하나 (backfill). 본문은 쓰지 않는다.
    제출 전(pending)이거나 제출 시각이 없는 리뷰는 저장하지 않는다."""
    if not isinstance(review, dict) or type(review.get('id')) is not int or not review.get('submitted_at'):
        return None
    try:
        return review_row(review, {'number': number})
    except (KeyError, TypeError, ValueError):
        return None


def apply(conn, event, payload):
    rid = repo_id(payload)
    if rid is None:
        return 'IGNORED'
    linked = conn.execute("SELECT 1 FROM repos WHERE repo_id = %s AND status = 'LINKED'", (rid,)).fetchone()
    if not linked:
        return 'IGNORED'

    action = payload.get('action')
    if event == 'push':
        rows = commit_rows(payload)
        reopened = False
    elif event == 'pull_request' and isinstance(payload.get('pull_request'), dict):
        rows, reopened = [item_row('PR', payload['pull_request'])], action == 'reopened'
    elif (event == 'pull_request_review' and isinstance(payload.get('review'), dict)
          and isinstance(payload.get('pull_request'), dict)):
        row = review_row(payload['review'], payload['pull_request'])
        rows, reopened = [row] if row else [], False
    elif event == 'issues' and isinstance(payload.get('issue'), dict):
        if action in ('deleted', 'transferred'):
            conn.execute("DELETE FROM activities WHERE repo_id = %s AND kind = 'ISSUE' AND ref = %s",
                         (rid, str(payload['issue']['number'])))
            return 'PROCESSED'
        rows, reopened = [item_row('ISSUE', payload['issue'])], action == 'reopened'
    else:
        return 'IGNORED'

    upsert(conn, rid, rows, reopened)
    return 'PROCESSED'


UPSERT = '''
    INSERT INTO activities(repo_id, kind, ref, actor_github_id, actor_login, title, state, occurred_at, url,
                           parent_ref, merged_at)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    ON CONFLICT (repo_id, kind, ref) DO UPDATE SET
        title = COALESCE(EXCLUDED.title, activities.title),
        url = COALESCE(EXCLUDED.url, activities.url),
        parent_ref = COALESCE(EXCLUDED.parent_ref, activities.parent_ref),
        -- 병합 시각이 없는 이벤트가 저장한 병합 시각을 지우지 않는다.
        merged_at = COALESCE(EXCLUDED.merged_at, activities.merged_at),
        -- 늦게 도착한 opened가 closed/merged를 되돌리지 않는다. PR의 merged와 리뷰의 dismissed는 최종 상태.
        state = CASE
            WHEN activities.state IN ('merged', 'dismissed') THEN activities.state
            WHEN activities.state IN ('closed', 'merged') AND EXCLUDED.state = 'open' AND NOT %s
                THEN activities.state
            ELSE COALESCE(EXCLUDED.state, activities.state) END
    WHERE activities.kind <> 'COMMIT' '''


def upsert(conn, rid, rows, reopened=False):
    """webhook과 backfill이 같은 규칙으로 쓴다. 커밋은 한 번 들어가면 바뀌지 않는다."""
    if not rows:
        return
    with conn.cursor() as cur:
        cur.executemany(UPSERT, [(rid, kind, ref, github_id, login, title, state, occurred_at, url,
                                  parent_ref, merged_at, reopened)
                                 for kind, ref, github_id, login, title, state, occurred_at, url, parent_ref, merged_at
                                 in rows])

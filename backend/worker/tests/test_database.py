"""Real PostgreSQL tests; each test gets a fresh, isolated schema."""
import json
import os
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock

import psycopg
from psycopg.conninfo import conninfo_to_dict
import pytest
from psycopg import sql

from backend.worker.config import Settings
from backend.worker.db import MIGRATIONS, connect, ensure_schema, migrate
from backend.worker.events import EventProcessor
from backend.worker.message import Message

HERE = Path(__file__).resolve().parent
# 2026URP docs/contracts/db-schema.sql (V1~V8 적용 결과). RepoScope 추가분은 V9, V10을 이어서 적용해 비교한다.
CONTRACT = HERE / 'contract' / 'db-schema.sql'
REPOSCOPE_ADDITIONS = ['V9__reposcope_additions.sql', 'V10__repo_readmes.sql']
MIGRATION_COUNT = 10


@pytest.fixture
def database():
    dsn = os.environ.get('WORKER_TEST_DB_DSN')
    if not dsn:
        pytest.skip('Set WORKER_TEST_DB_DSN to run real PostgreSQL worker tests')
    schema = 'worker_test_' + uuid.uuid4().hex
    admin = psycopg.connect(dsn, autocommit=True)
    admin.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))

    def factory():
        conn = psycopg.connect(dsn, autocommit=True)
        conn.execute(sql.SQL('SET search_path TO {}').format(sql.Identifier(schema)))
        return conn

    try:
        with factory() as conn:
            migrate(conn)
        yield factory
    finally:
        admin.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(schema)))
        admin.close()


def fixture(name):
    return json.loads((HERE / 'fixtures' / 'github' / (name + '.json')).read_text(encoding='utf-8'))


def send(processor, name, delivery=None, payload=None, event=None):
    message = Message.parse({'schema_version': 1, 'source': 'webhook',
                             'delivery_id': delivery or str(uuid.uuid4()),
                             'event': event or name.split('.')[0],
                             's3_key': '2026-11-03/test.json', 'received_at': '2026-11-03T12:00:00Z'})
    return processor.process(message, payload if payload is not None else fixture(name))


COLUMNS = '''SELECT table_name, column_name, data_type, is_nullable
    FROM information_schema.columns WHERE table_schema = current_schema()
    AND table_name <> 'worker_schema_migrations'
    ORDER BY table_name, ordinal_position'''
CONSTRAINTS = '''SELECT t.relname, c.conname, c.contype, pg_get_constraintdef(c.oid)
    FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
    WHERE t.relnamespace = current_schema()::regnamespace AND t.relname <> 'worker_schema_migrations'
    ORDER BY 1, 2'''
INDEXES = '''SELECT tablename, indexname FROM pg_indexes
    WHERE schemaname = current_schema() AND tablename <> 'worker_schema_migrations'
    ORDER BY 1, 2'''


def schema_shape(conn):
    return [conn.execute(query).fetchall() for query in (COLUMNS, CONSTRAINTS, INDEXES)]


def test_migrations_can_restart_and_match_contract(database):
    with database() as conn:
        migrate(conn)
        assert conn.execute('SELECT count(*) FROM worker_schema_migrations').fetchone() == (MIGRATION_COUNT,)
        actual = schema_shape(conn)
        reference = 'contract_reference_' + uuid.uuid4().hex
        with conn.transaction():
            conn.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(reference)))
            conn.execute(sql.SQL('SET LOCAL search_path TO {}').format(sql.Identifier(reference)))
            conn.execute(CONTRACT.read_text(encoding='utf-8'), prepare=False)
            for name in REPOSCOPE_ADDITIONS:
                conn.execute((MIGRATIONS / name).read_text(encoding='utf-8'), prepare=False)
            expected = schema_shape(conn)
            conn.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(reference)))
        assert actual == expected


def test_migrations_never_touch_the_capstone_schema():
    for path in MIGRATIONS.glob('V*.sql'):
        assert 'capstone' not in path.read_text(encoding='utf-8').lower(), path.name


def test_worker_connection_sees_only_the_ingest_schema(monkeypatch):
    dsn = os.environ.get('WORKER_TEST_DB_DSN')
    if not dsn:
        pytest.skip('Set WORKER_TEST_DB_DSN to run real PostgreSQL worker tests')
    params = conninfo_to_dict(dsn)
    schema = 'worker_ingest_' + uuid.uuid4().hex[:16]
    settings = Settings(db={'host': params.get('host'), 'port': params.get('port', '5432'),
                            'dbname': params.get('dbname'), 'user': params.get('user'),
                            'password': params.get('password'), 'sslmode': params.get('sslmode', 'disable')},
                        queue_url='queue', raw_bucket='bucket', region='ap-northeast-2',
                        app_id='', app_key_file='', schema=schema)
    admin = psycopg.connect(dsn, autocommit=True)
    try:
        with connect(settings) as conn:
            ensure_schema(conn, settings.schema)
            ensure_schema(conn, settings.schema)                    # 다시 시작해도 안전
            migrate(conn)
            assert conn.execute('SELECT current_schemas(false)').fetchone() == ([schema],)
            assert conn.execute('SELECT count(*) FROM worker_schema_migrations').fetchone() == (MIGRATION_COUNT,)
        tables = {row[0] for row in admin.execute(
            'SELECT table_name FROM information_schema.tables WHERE table_schema = %s', (schema,))}
        assert {'activities', 'repos', 'processed_deliveries', 'worker_schema_migrations'} <= tables
    finally:
        admin.execute(sql.SQL('DROP SCHEMA IF EXISTS {} CASCADE').format(sql.Identifier(schema)))
        admin.close()


def test_created_and_concurrent_duplicate_deliveries(database):
    processor = EventProcessor(database)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: send(processor, 'installation.created', 'same-delivery'), range(2)))
    assert sorted(results) == ['DUPLICATE', 'PROCESSED']
    with database() as conn:
        assert conn.execute('SELECT status, team_id FROM installations').fetchall() == [('ACTIVE', None)]
        assert conn.execute('SELECT status, project_id FROM repos').fetchall() == [('LINKED', None)] * 2
        assert conn.execute('SELECT status FROM processed_deliveries').fetchall() == [('PROCESSED',)]


def test_applied_migration_is_immutable_but_line_endings_are_portable(database, tmp_path):
    for other in MIGRATIONS.glob('V*.sql'):
        (tmp_path / other.name).write_bytes(other.read_bytes())
    migration = tmp_path / 'V1__m1_init.sql'
    content = (MIGRATIONS / migration.name).read_text(encoding='utf-8')
    migration.write_bytes(content.replace('\n', '\r\n').encode('utf-8'))
    with database() as conn:
        migrate(conn, tmp_path)
        migration.write_text(content + '\nDROP TABLE repos;\n', encoding='utf-8')
        with pytest.raises(RuntimeError, match='checksum mismatch'):
            migrate(conn, tmp_path)
        assert conn.execute('SELECT count(*) FROM repos').fetchone() == (0,)


def test_repo_changes_before_created_and_late_created_preserves_unlink(database):
    processor = EventProcessor(database)
    send(processor, 'installation_repositories.removed')
    send(processor, 'installation.created')
    send(processor, 'installation_repositories.added')
    with database() as conn:
        assert conn.execute('SELECT repo_id, status FROM repos ORDER BY repo_id').fetchall() == [
            (30000001, 'LINKED'), (30000002, 'UNLINKED'), (30000003, 'LINKED')]


def test_suspend_unsuspend_deleted_and_late_created(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    for name, state in [('suspend', 'SUSPENDED'), ('unsuspend', 'ACTIVE'), ('deleted', 'REMOVED')]:
        send(processor, 'installation.' + name)
        with database() as conn:
            assert conn.execute('SELECT status FROM installations').fetchone() == (state,)
    send(processor, 'installation.created')
    send(processor, 'installation_repositories.added')
    with database() as conn:
        assert conn.execute('SELECT status, removed_at IS NOT NULL FROM installations').fetchone() == ('REMOVED', True)
        assert conn.execute("SELECT count(*) FROM repos WHERE status = 'LINKED'").fetchone() == (0,)


def test_database_failure_rolls_back_claim_and_partial_rows_then_retries(database):
    processor = EventProcessor(database)
    bad = fixture('installation.created')
    bad['repositories'][1]['private'] = 'not-a-boolean'
    with pytest.raises(ValueError):
        send(processor, 'installation.created', 'retry-me', bad)
    with database() as conn:
        for table in ('processed_deliveries', 'installations', 'repos'):
            assert conn.execute(sql.SQL('SELECT count(*) FROM {}').format(sql.Identifier(table))).fetchone() == (0,)
    assert send(processor, 'installation.created', 'retry-me') == 'PROCESSED'


@pytest.mark.parametrize('name', ['push', 'pull_request.opened', 'pull_request_review.submitted', 'issues.opened'])
def test_activity_events_for_unregistered_repos_are_ignored(database, name):
    processor = EventProcessor(database)
    assert send(processor, name) == 'IGNORED'                       # repo 행 없음
    assert send(processor, name, payload={'commits': [{'email': 'user@example.com'}]}) == 'IGNORED'
    with database() as conn:
        assert conn.execute('SELECT count(*) FROM activities').fetchone() == (0,)
        assert conn.execute('SELECT count(*) FROM repos').fetchone() == (0,)


def activities(database, kind):
    with database() as conn:
        return {r[0]: r[1:] for r in conn.execute(
            'SELECT ref, actor_github_id, actor_login, title, state, occurred_at, url FROM activities '
            'WHERE kind = %s ORDER BY ref', (kind,)).fetchall()}


def test_push_stores_each_commit_title_only(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    assert send(processor, 'push') == 'PROCESSED'
    commits = activities(database, 'COMMIT')
    first = commits['f1e2d3c4b5a6f7e8d9c0b1a2f3e4d5c6b7a8f9e0']
    assert first[1:3] == ('example-student', '로그인 화면 추가') and first[3] is None
    assert first[4].isoformat() == '2026-11-03T01:00:00+00:00'
    assert commits['a2c4e6a8b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a0'][1] == 'example-teammate'
    with database() as conn:
        dump = ' '.join(r[0] for r in conn.execute('SELECT row_to_json(a)::text FROM activities a'))
    assert '@' not in dump and '폼 검증' not in dump
    assert send(processor, 'push') == 'PROCESSED'                   # 같은 내용의 새 delivery
    assert len(activities(database, 'COMMIT')) == 2


def test_tag_push_and_branch_delete_store_nothing(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    tag = fixture('push')
    tag['ref'] = 'refs/tags/v1'
    deleted = fixture('push')
    deleted['deleted'] = True
    assert send(processor, 'push', payload=tag) == 'PROCESSED'
    assert send(processor, 'push', payload=deleted) == 'PROCESSED'
    assert activities(database, 'COMMIT') == {}


def test_unlinked_repo_events_are_ignored(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    send(processor, 'installation_repositories.removed')
    removed = fixture('installation_repositories.removed')['repositories_removed'][0]['id']
    payload = fixture('push')
    payload['repository']['id'] = removed
    assert send(processor, 'push', payload=payload) == 'IGNORED'


def test_pull_request_merge_is_final_and_late_open_does_not_regress(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    assert send(processor, 'pull_request.opened') == 'PROCESSED'
    assert activities(database, 'PR')['7'][3] == 'open'
    send(processor, 'pull_request.closed')
    send(processor, 'pull_request.opened')                          # 늦게 도착한 opened
    prs = activities(database, 'PR')
    assert list(prs) == ['7']
    assert prs['7'][:4] == (20000002, 'example-teammate', '로그인 화면 추가', 'merged')


def test_unmerged_closed_pr_is_not_reopened_by_late_open(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    closed = fixture('pull_request.closed')
    closed['pull_request'].update(merged=False, merged_at=None, merge_commit_sha=None, merged_by=None)
    send(processor, 'pull_request', payload=closed)
    send(processor, 'pull_request.opened')                          # 늦게 도착한 opened
    with database() as conn:
        assert conn.execute("SELECT state, merged_at FROM activities WHERE kind = 'PR'").fetchall() == [
            ('closed', None)]


def merged_at(database):
    with database() as conn:
        row = conn.execute("SELECT merged_at FROM activities WHERE kind = 'PR' AND ref = '7'").fetchone()
    return row[0].isoformat() if row and row[0] else None


def test_merged_at_is_not_cleared_by_events_without_it(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    send(processor, 'pull_request.opened')
    assert merged_at(database) is None
    send(processor, 'pull_request.closed')
    assert merged_at(database) == '2026-11-03T03:30:00+00:00'
    send(processor, 'pull_request.opened')                          # 늦게 도착한 opened (merged_at 없음)
    edited = fixture('pull_request.closed')
    edited['action'] = 'edited'
    del edited['pull_request']['merged_at']
    send(processor, 'pull_request', payload=edited)
    assert merged_at(database) == '2026-11-03T03:30:00+00:00'
    with database() as conn:
        assert conn.execute("SELECT state FROM activities WHERE kind = 'PR'").fetchall() == [('merged',)]


def reviews(database):
    with database() as conn:
        return {r[0]: r[1:] for r in conn.execute(
            'SELECT ref, parent_ref, actor_github_id, actor_login, title, state, occurred_at, merged_at, url '
            "FROM activities WHERE kind = 'REVIEW' ORDER BY ref").fetchall()}


def test_review_stores_parent_pr_and_lowercase_state_before_pr_row(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    upper = fixture('pull_request_review.submitted')
    upper['review']['state'] = 'APPROVED'
    assert send(processor, 'pull_request_review', payload=upper) == 'PROCESSED'   # PR 행 없이 먼저 도착
    review = reviews(database)['50000001']
    assert review[:5] == ('7', 20000001, 'example-student', None, 'approved')
    assert review[5].isoformat() == '2026-11-03T02:40:00+00:00' and review[6] is None
    assert review[7] == 'https://github.com/example-student/team-project/pull/7#pullrequestreview-50000001'
    with database() as conn:
        dump = ' '.join(r[0] for r in conn.execute('SELECT row_to_json(a)::text FROM activities a'))
    assert '@' not in dump and '검증 로직 확인' not in dump


@pytest.mark.parametrize('state', ['pending', 'PENDING'])
def test_pending_review_is_not_stored(database, state):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    pending = fixture('pull_request_review.submitted')
    pending['review'].update(state=state, submitted_at=None)
    assert send(processor, 'pull_request_review', payload=pending) == 'PROCESSED'
    assert reviews(database) == {}


def test_submitted_review_without_submitted_at_fails(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    broken = fixture('pull_request_review.submitted')
    broken['review']['submitted_at'] = None
    with pytest.raises(ValueError):
        send(processor, 'pull_request_review', 'retry-review', broken)
    with database() as conn:
        assert conn.execute("SELECT count(*) FROM processed_deliveries WHERE delivery_id = 'retry-review'"
                            ).fetchone() == (0,)
    assert reviews(database) == {}


def test_duplicate_review_is_stored_once(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    assert send(processor, 'pull_request_review.submitted', 'same-review') == 'PROCESSED'
    assert send(processor, 'pull_request_review.submitted', 'same-review') == 'DUPLICATE'
    assert send(processor, 'pull_request_review.submitted') == 'PROCESSED'     # 같은 리뷰, 다른 delivery
    assert list(reviews(database)) == ['50000001']


def test_dismissed_review_is_final(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    send(processor, 'pull_request_review.submitted')
    send(processor, 'pull_request_review.dismissed')
    send(processor, 'pull_request_review.submitted')                # 늦게 도착한 submitted
    edited = fixture('pull_request_review.submitted')
    edited['action'] = 'edited'
    send(processor, 'pull_request_review', payload=edited)
    assert reviews(database)['50000001'][4] == 'dismissed'


def test_review_conflict_keeps_actor_and_occurred_at(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    send(processor, 'pull_request_review.submitted')
    changed = fixture('pull_request_review.submitted')
    changed['review']['user'] = {'login': 'example-teammate', 'id': 20000002, 'type': 'User'}
    changed['review'].update(submitted_at='2026-11-04T00:00:00Z', state='commented')
    send(processor, 'pull_request_review', payload=changed)
    review = reviews(database)['50000001']
    assert review[1:3] == (20000001, 'example-student') and review[4] == 'commented'
    assert review[5].isoformat() == '2026-11-03T02:40:00+00:00'


def test_issue_close_reopen_and_delete(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    send(processor, 'issues.opened')
    send(processor, 'issues.closed')
    send(processor, 'issues.opened')                                # 늦게 도착한 opened
    assert activities(database, 'ISSUE')['12'][3] == 'closed'
    reopened = fixture('issues.opened')
    reopened['action'] = 'reopened'
    send(processor, 'issues', payload=reopened)
    assert activities(database, 'ISSUE')['12'][3] == 'open'
    deleted = fixture('issues.closed')
    deleted['action'] = 'deleted'
    send(processor, 'issues', payload=deleted)
    assert activities(database, 'ISSUE') == {}


def test_all_selection_fetches_full_repository_list(database):
    payload = fixture('installation.created')
    payload['installation']['repository_selection'] = 'all'
    github = Mock()
    github.repositories.return_value = payload['repositories'] + fixture('installation_repositories.added')['repositories_added']
    assert send(EventProcessor(database, github), 'installation.created', payload=payload) == 'PROCESSED'
    github.repositories.assert_called_once_with(10000001)
    with database() as conn:
        assert conn.execute('SELECT count(*) FROM repos').fetchone() == (3,)


def test_missing_app_key_keeps_payload_repos_and_github_failure_is_retryable(database):
    payload = fixture('installation.created')
    payload['installation']['repository_selection'] = 'all'
    github = Mock()
    github.repositories.side_effect = RuntimeError('API unavailable')
    with pytest.raises(RuntimeError):
        send(EventProcessor(database, github), 'installation.created', 'retry-all', payload)
    with database() as conn:
        assert conn.execute('SELECT count(*) FROM processed_deliveries').fetchone() == (0,)
    assert send(EventProcessor(database), 'installation.created', 'retry-all', payload) == 'PROCESSED'


def test_upsert_preserves_team_and_project_associations(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    with database() as conn:
        conn.execute("INSERT INTO users(id, github_id, login) VALUES (1, 20000001, 'example-student')")
        conn.execute("INSERT INTO teams(id, name, created_by) VALUES (77, 'team', 1)")
        conn.execute("INSERT INTO projects(id, team_id, title, status) VALUES (88, 77, 'project', 'ACTIVE')")
        conn.execute('UPDATE installations SET team_id = 77')
        conn.execute('UPDATE repos SET project_id = 88')
    renamed = fixture('installation.created')
    renamed['repositories'][0]['full_name'] = 'example-student/renamed'
    send(processor, 'installation.created', payload=renamed)
    with database() as conn:
        assert conn.execute('SELECT team_id FROM installations').fetchone() == (77,)
        assert conn.execute('SELECT DISTINCT project_id FROM repos').fetchall() == [(88,)]
        assert conn.execute('SELECT full_name FROM repos WHERE repo_id = 30000001').fetchone() == ('example-student/renamed',)


def test_repo_readmes_matches_the_data_model(database):
    with database() as conn:
        columns = conn.execute('''SELECT column_name, data_type, is_nullable FROM information_schema.columns
                                  WHERE table_schema = current_schema() AND table_name = 'repo_readmes'
                                  ORDER BY ordinal_position''').fetchall()
        assert columns == [('repo_id', 'bigint', 'NO'), ('path', 'text', 'NO'), ('content', 'text', 'NO'),
                           ('etag', 'text', 'YES'), ('fetched_at', 'timestamp with time zone', 'NO')]
    send(EventProcessor(database), 'installation.created')
    with database() as conn:
        conn.execute("INSERT INTO repo_readmes(repo_id, path, content) VALUES (30000001, 'README.md', %s)",
                     ('a' * 524288,))
        with pytest.raises(psycopg.errors.CheckViolation):
            conn.execute("INSERT INTO repo_readmes(repo_id, path, content) VALUES (30000002, 'README.md', %s)",
                         ('가' * 174763,))                          # 524,289바이트


# ---------------------------------------------------------------- 과거 기록 (backfill)

import base64  # noqa: E402
from datetime import datetime, timedelta, timezone  # noqa: E402
from urllib.parse import parse_qsl  # noqa: E402

from backend.worker import backfill, readme  # noqa: E402
from backend.worker.github import GitHubError  # noqa: E402

# 가짜 GitHub 응답에 넣는 값. 실제 사람의 이름, 이메일, 토큰이 아니다.
EMAIL = 'person@example.com'
AUTHOR_NAME = 'Example Author'
TOKEN = 'installation-token'
REPO = '/repos/example-student/team-project'
PRIVATE_REPO = '/repos/example-student/team-project-private'


def gh_commit(sha, message, date, login=None, uid=None):
    return {'sha': sha, 'html_url': f'https://github.com/example-student/team-project/commit/{sha}',
            'commit': {'message': message, 'author': {'name': AUTHOR_NAME, 'email': EMAIL, 'date': date},
                       'committer': {'name': AUTHOR_NAME, 'email': EMAIL, 'date': date}},
            'author': {'login': login, 'id': uid} if login else None}


def gh_item(number, created, state='open', merged_at=None, pr=False):
    item = {'number': number, 'title': f'item {number}', 'state': state, 'created_at': created,
            'html_url': f'https://github.com/example-student/team-project/issues/{number}',
            'user': {'login': 'example-student', 'id': 20000001}, 'body': 'secret body ' + EMAIL}
    if merged_at:
        item['merged_at'] = merged_at
    if pr:
        item['pull_request'] = {'url': 'https://api.github.com/x'}
    return item


def gh_review(review_id, state, submitted, pr=2, login='example-teammate', uid=20000002):
    return {'id': review_id, 'state': state, 'submitted_at': submitted, 'body': 'review body ' + EMAIL,
            'user': {'login': login, 'id': uid},
            'html_url': f'https://github.com/example-student/team-project/pull/{pr}#pullrequestreview-{review_id}'}


def gh_readme(text, etag='"readme-1"', path='README.md'):
    raw = text if isinstance(text, bytes) else text.encode('utf-8')
    encoded = base64.encodebytes(raw).decode('ascii')               # GitHub처럼 줄바꿈이 들어간 base64
    return lambda sent: (200, etag, {'path': path, 'size': len(raw), 'encoding': 'base64', 'content': encoded})


class FakeGitHub:
    """routes: 경로(쿼리 제외) -> 함수. 목록·저장소는 함수(params) -> 값 또는 GitHubError,
    README(…/readme)는 함수(보낸 ETag) -> (상태, ETag, 본문) 또는 GitHubError. README 경로가 없으면 404."""
    def __init__(self, routes, token_error=None):
        self.routes, self.token_error, self.calls, self.etags = routes, token_error, [], []

    def installation_token(self, installation_id):
        if self.token_error:
            raise self.token_error
        return TOKEN

    def get(self, path, token):
        assert token == TOKEN
        base, _, query = path.partition('?')
        self.calls.append(path)
        result = self.routes.get(base, lambda params: [])(dict(parse_qsl(query)))
        if isinstance(result, Exception):
            raise result
        return result

    def conditional_get(self, path, token, etag=None):
        assert token == TOKEN and path.endswith('/readme')
        self.calls.append(path)
        self.etags.append(etag)
        result = self.routes.get(path, lambda sent: GitHubError(status=404))(etag)
        if isinstance(result, Exception):
            raise result
        return result


def jobs(database):
    with database() as conn:
        return {row[0]: row[1:] for row in conn.execute(
            'SELECT repo_id, status, items, last_error, attempts FROM backfill_jobs ORDER BY repo_id')}


def readmes(database):
    with database() as conn:
        return {row[0]: row[1:] for row in conn.execute(
            'SELECT repo_id, path, content, etag FROM repo_readmes ORDER BY repo_id')}


def make_due(database):
    with database() as conn:
        conn.execute('UPDATE backfill_jobs SET next_attempt_at = now()')


def full_history_routes():
    main = [gh_commit('c1', 'first line\n\nbody with ' + EMAIL, '2025-03-01T00:00:00Z', 'example-student', 20000001),
            gh_commit('c2', 'no linked account', '2025-02-01T00:00:00Z')]

    def commits(params):
        if params.get('sha') == 'feature':
            return [gh_commit('c3', 'feature work', '2025-04-01T00:00:00Z', 'example-teammate', 20000002)] + main
        return main

    return {
        f'{REPO}/commits': commits,
        f'{REPO}/branches': lambda params: [{'name': 'main'}, {'name': 'feature'}],
        f'{REPO}/pulls': lambda params: [gh_item(2, '2025-05-01T00:00:00Z', 'closed', merged_at='2025-05-02T00:00:00Z'),
                                         gh_item(1, '2024-01-01T00:00:00Z')],
        f'{REPO}/pulls/2/reviews': lambda params: [gh_review(901, 'APPROVED', '2025-05-01T10:00:00Z'),
                                                   gh_review(902, 'PENDING', None)],
        f'{REPO}/pulls/1/reviews': lambda params: [gh_review(903, 'COMMENTED', '2024-01-02T00:00:00Z', pr=1)],
        f'{REPO}/issues': lambda params: [gh_item(5, '2025-06-01T00:00:00Z'),
                                          gh_item(2, '2025-05-01T00:00:00Z', pr=True),
                                          gh_item(3, '2024-02-01T00:00:00Z', 'closed')],
        f'{REPO}/readme': gh_readme('# Team project\n'),
        f'{PRIVATE_REPO}/commits': lambda params: GitHubError(status=409),
        f'{PRIVATE_REPO}/issues': lambda params: GitHubError(status=410),
    }


def test_backfill_imports_full_history_once_without_emails_or_bodies(database):
    send(EventProcessor(database), 'installation.created')
    github = FakeGitHub(full_history_routes())
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 2
    with database() as conn:
        rows = conn.execute('''SELECT kind, ref, parent_ref, actor_github_id, actor_login, title, state, merged_at
                               FROM activities WHERE repo_id = 30000001 ORDER BY kind, ref''').fetchall()
        everything = conn.execute('SELECT * FROM activities').fetchall()
    merged = datetime(2025, 5, 2, tzinfo=timezone.utc)
    assert rows == [('COMMIT', 'c1', None, 20000001, 'example-student', 'first line', None, None),
                    ('COMMIT', 'c2', None, None, None, 'no linked account', None, None),
                    ('COMMIT', 'c3', None, 20000002, 'example-teammate', 'feature work', None, None),
                    ('ISSUE', '3', None, 20000001, 'example-student', 'item 3', 'closed', None),
                    ('ISSUE', '5', None, 20000001, 'example-student', 'item 5', 'open', None),
                    ('PR', '1', None, 20000001, 'example-student', 'item 1', 'open', None),
                    ('PR', '2', None, 20000001, 'example-student', 'item 2', 'merged', merged),
                    ('REVIEW', '901', '2', 20000002, 'example-teammate', None, 'approved', None),
                    ('REVIEW', '903', '1', 20000002, 'example-teammate', None, 'commented', None)]
    text = str(everything)
    assert EMAIL not in text and AUTHOR_NAME not in text and 'secret body' not in text and 'review body' not in text
    assert jobs(database) == {30000001: ('DONE', 9, None, 1), 30000002: ('DONE', 0, None, 1)}
    assert readmes(database) == {30000001: ('README.md', '# Team project\n', '"readme-1"')}  # 비공개 쪽은 404
    # main 브랜치는 기본 브랜치에서 이미 본 커밋뿐이라 한 쪽만 읽고 멈춘다
    assert sum('sha=main' in call for call in github.calls) == 1

    calls = len(github.calls)
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 0
    assert len(github.calls) == calls


def test_backfill_window_stops_at_since(database):
    send(EventProcessor(database), 'installation.created')
    now = datetime.now(timezone.utc)
    recent, old = (now - timedelta(days=10)).isoformat(), (now - timedelta(days=200)).isoformat()
    seen = {}

    def commits(params):
        seen.setdefault('since', params.get('since'))
        return [gh_commit('r1', 'recent', recent)]

    github = FakeGitHub({f'{REPO}/commits': commits,
                         f'{REPO}/pulls': lambda params: [gh_item(9, recent), gh_item(8, old), gh_item(7, old)],
                         f'{REPO}/pulls/9/reviews': lambda params: [gh_review(950, 'APPROVED', recent, pr=9),
                                                                    gh_review(951, 'COMMENTED', old, pr=9)],
                         f'{REPO}/issues': lambda params: [gh_item(4, old)]})
    backfill.run_once(database, github, days=90, budget_seconds=30)
    with database() as conn:
        refs = conn.execute("SELECT kind, ref FROM activities WHERE repo_id = 30000001 ORDER BY kind, ref").fetchall()
    assert refs == [('COMMIT', 'r1'), ('PR', '9'), ('REVIEW', '950')]
    assert seen['since'].endswith('Z')
    assert not any('/pulls/8/' in call for call in github.calls)    # 기간 밖 PR의 리뷰는 읽지 않는다


def test_all_repository_installs_get_readme_only_until_selected(database):
    payload = fixture('installation.created')
    payload['installation']['repository_selection'] = 'all'
    github_app = Mock()
    github_app.repositories.return_value = payload['repositories']
    send(EventProcessor(database, github_app), 'installation.created', payload=payload)
    github = FakeGitHub(full_history_routes())
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 2
    assert jobs(database) == {30000001: ('SKIPPED', None, 'all_repositories', 1),
                              30000002: ('SKIPPED', None, 'all_repositories', 1)}
    assert all(call.endswith('/readme') for call in github.calls)  # 활동 목록은 읽지 않는다
    assert list(readmes(database)) == [30000001]
    with database() as conn:
        assert conn.execute('SELECT count(*) FROM activities').fetchone() == (0,)
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 0   # 계속 다시 하지 않는다

    # 나중에 팀 저장소만 고르도록 바꾸면 그때 가져온다. 빠진 저장소는 가져오지 않는다
    with database() as conn:
        conn.execute("UPDATE installations SET repo_selection = 'selected'")
        conn.execute("UPDATE repos SET status = 'UNLINKED' WHERE repo_id = 30000002")
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 1
    assert jobs(database)[30000001] == ('DONE', 9, None, 1)


def test_backfill_retries_then_fails_and_missing_repo_fails_at_once(database):
    send(EventProcessor(database), 'installation.created')
    flaky = FakeGitHub({}, token_error=GitHubError(status=502))
    backfill.run_once(database, flaky, days=0, budget_seconds=30)
    assert jobs(database)[30000001] == ('PENDING', None, 'http_502', 1)
    assert backfill.run_once(database, flaky, days=0, budget_seconds=30) == 0  # 아직 기한 전
    for _ in range(backfill.MAX_ATTEMPTS - 1):
        make_due(database)
        backfill.run_once(database, flaky, days=0, budget_seconds=30)
    assert jobs(database)[30000001] == ('FAILED', None, 'http_502', backfill.MAX_ATTEMPTS)

    with database() as conn:
        conn.execute('DELETE FROM backfill_jobs')
    gone = FakeGitHub({f'{REPO}/commits': lambda params: GitHubError(status=404)})
    backfill.run_once(database, gone, days=0, budget_seconds=30)
    assert jobs(database)[30000001] == ('FAILED', None, 'http_404', 1)


def test_readme_failure_retries_the_backfill_without_saving(database):
    send(EventProcessor(database), 'installation.created')
    routes = full_history_routes()
    routes[f'{REPO}/readme'] = lambda sent: GitHubError(status=503)
    backfill.run_once(database, FakeGitHub(routes), days=0, budget_seconds=30)
    assert jobs(database)[30000001] == ('PENDING', None, 'http_503', 1)
    with database() as conn:
        assert conn.execute('SELECT count(*) FROM activities WHERE repo_id = 30000001').fetchone() == (0,)
    make_due(database)
    backfill.run_once(database, FakeGitHub(full_history_routes()), days=0, budget_seconds=30)
    assert jobs(database)[30000001] == ('DONE', 9, None, 2)


def test_backfill_uses_the_webhook_upsert_rules(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    send(processor, 'push')
    send(processor, 'issues.closed')
    send(processor, 'pull_request.closed')                          # PR 7 merged
    send(processor, 'pull_request_review.dismissed')                # 리뷰 50000001 dismissed
    with database() as conn:
        sha, title = conn.execute("SELECT ref, title FROM activities WHERE kind = 'COMMIT' ORDER BY ref").fetchall()[0]
        before = conn.execute('SELECT count(*) FROM activities').fetchone()[0]
    github = FakeGitHub({
        f'{REPO}/commits': lambda params: [gh_commit(sha, 'rewritten title', '2026-11-03T00:00:00Z')],
        f'{REPO}/pulls': lambda params: [gh_item(7, '2026-11-03T02:00:00Z', 'open')],   # 오래된 상태, 병합 시각 없음
        f'{REPO}/pulls/7/reviews': lambda params: [gh_review(50000001, 'APPROVED', '2026-11-03T02:40:00Z', pr=7)],
        f'{REPO}/issues': lambda params: [gh_item(12, '2026-11-03T02:00:00Z', 'open')]})
    backfill.run_once(database, github, days=0, budget_seconds=30)
    with database() as conn:
        assert conn.execute("SELECT title FROM activities WHERE kind = 'COMMIT' AND ref = %s", (sha,)).fetchone() == (title,)
        assert conn.execute("SELECT state FROM activities WHERE kind = 'ISSUE' AND ref = '12'").fetchone() == ('closed',)
        assert conn.execute("SELECT state, merged_at FROM activities WHERE kind = 'PR' AND ref = '7'").fetchone() == (
            'merged', datetime(2026, 11, 3, 3, 30, tzinfo=timezone.utc))
        assert conn.execute("SELECT state FROM activities WHERE kind = 'REVIEW'").fetchall() == [('dismissed',)]
        assert conn.execute('SELECT count(*) FROM activities').fetchone()[0] == before  # 중복 없음


def test_backfill_needs_the_app_key(database):
    send(EventProcessor(database), 'installation.created')
    assert backfill.run_once(database, None, days=0, budget_seconds=30) == 0
    assert jobs(database) == {}


def test_backfill_skips_deleted_branches_and_disabled_pull_requests(database):
    send(EventProcessor(database), 'installation.created')
    routes = full_history_routes()
    base_commits = routes[f'{REPO}/commits']

    def commits(params):
        if params.get('sha') == 'deleted-meanwhile':
            return GitHubError(status=404)  # /branches 뒤에 지워진 브랜치
        return base_commits(params)

    routes[f'{REPO}/commits'] = commits
    routes[f'{REPO}/branches'] = lambda params: [{'name': 'main'}, {'name': 'deleted-meanwhile'}, {'name': 'feature'}]
    routes[f'{REPO}/pulls'] = lambda params: GitHubError(status=404)  # PR을 끈 저장소
    backfill.run_once(database, FakeGitHub(routes), days=0, budget_seconds=30)
    with database() as conn:
        kinds = conn.execute("SELECT kind, count(*) FROM activities WHERE repo_id = 30000001 GROUP BY kind ORDER BY kind").fetchall()
    assert kinds == [('COMMIT', 3), ('ISSUE', 2)]
    assert jobs(database)[30000001] == ('DONE', 5, None, 1)


def test_backfill_of_a_fork_starts_at_the_fork_and_follows_renames(database):
    send(EventProcessor(database), 'installation.created')
    new = '/repos/example-student/renamed-project'
    seen = {}

    def commits(params):
        seen.setdefault('since', params.get('since'))
        return [gh_commit('ours', 'team work', '2026-10-01T00:00:00Z')]

    github = FakeGitHub({
        REPO: lambda params: {'full_name': 'example-student/renamed-project',
                              'fork': True, 'created_at': '2026-09-01T00:00:00Z'},
        f'{new}/commits': commits,
        f'{new}/pulls': lambda params: [gh_item(1, '2026-09-15T00:00:00Z')],
    })
    backfill.run_once(database, github, days=0, budget_seconds=30)
    assert seen['since'] == '2026-09-01T00:00:00Z'  # 포크한 시점부터
    # 바뀐 이름으로 읽음 (README는 저장한 이름으로 부르고 GitHub가 새 이름으로 넘겨준다)
    assert all('/team-project/' not in call for call in github.calls if not call.endswith('/readme'))
    with database() as conn:
        refs = conn.execute("SELECT kind, ref FROM activities WHERE repo_id = 30000001 ORDER BY kind, ref").fetchall()
    assert refs == [('COMMIT', 'ours'), ('PR', '1')]
    with database() as conn:
        assert conn.execute('SELECT status FROM backfill_jobs WHERE repo_id = 30000001').fetchone() == ('DONE',)


def test_backfill_marks_truncated_lists_and_retries_failed_jobs_a_day_later(database, monkeypatch):
    send(EventProcessor(database), 'installation.created')
    monkeypatch.setattr(backfill, 'MAX_PAGES', 1)
    full_page = [gh_commit(f'c{i}', f'commit {i}', '2026-10-01T00:00:00Z') for i in range(backfill.PER_PAGE)]
    backfill.run_once(database, FakeGitHub({f'{REPO}/commits': lambda params: full_page}), days=0, budget_seconds=30)
    assert jobs(database)[30000001] == ('DONE', 100, 'truncated', 1)

    gone = FakeGitHub({PRIVATE_REPO: lambda params: GitHubError(status=404)})
    with database() as conn:
        conn.execute("UPDATE backfill_jobs SET status = 'PENDING', next_attempt_at = now() WHERE repo_id = 30000002")
    backfill.run_once(database, gone, days=0, budget_seconds=30)
    assert jobs(database)[30000002][:3] == ('FAILED', None, 'http_404')
    with database() as conn:
        conn.execute("UPDATE backfill_jobs SET finished_at = now() - interval '2 days' WHERE repo_id = 30000002")
        backfill.sweep(conn, 0)
    assert jobs(database)[30000002] == ('PENDING', None, None, 0)


def test_reviews_are_read_for_the_most_recent_pull_requests_only(database, monkeypatch):
    send(EventProcessor(database), 'installation.created')
    monkeypatch.setattr(backfill, 'REVIEW_PR_LIMIT', 1)
    github = FakeGitHub(full_history_routes())
    backfill.run_once(database, github, days=0, budget_seconds=30)
    assert [c for c in github.calls if '/reviews' in c] == [f'{REPO}/pulls/2/reviews?per_page=100&page=1']
    with database() as conn:
        assert conn.execute("SELECT ref FROM activities WHERE kind = 'REVIEW'").fetchall() == [('901',)]
    assert jobs(database)[30000001] == ('DONE', 8, 'truncated', 1)


def test_removed_then_re_added_repo_is_backfilled_again(database):
    processor = EventProcessor(database)
    send(processor, 'installation.created')
    github = FakeGitHub(full_history_routes())
    github.routes[f'{PRIVATE_REPO}/readme'] = gh_readme('# Private\n')
    backfill.run_once(database, github, days=0, budget_seconds=30)
    assert jobs(database)[30000002] == ('DONE', 0, None, 1) and 30000002 in readmes(database)

    send(processor, 'installation_repositories.removed')            # 30000002가 설치에서 빠짐
    assert jobs(database)[30000002][:3] == ('SKIPPED', None, 'not_linked')
    assert 30000002 not in readmes(database) and 30000001 in readmes(database)
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 0   # 빠진 동안은 가져오지 않는다

    added = fixture('installation_repositories.added')
    added['repositories_added'] = fixture('installation_repositories.removed')['repositories_removed']
    send(processor, 'installation_repositories', payload=added)
    github.routes[f'{PRIVATE_REPO}/commits'] = lambda params: [gh_commit('gap', 'while removed', '2026-11-02T00:00:00Z')]
    assert backfill.run_once(database, github, days=0, budget_seconds=30) == 1
    assert jobs(database)[30000002] == ('DONE', 1, None, 1)
    assert readmes(database)[30000002][1] == '# Private\n'
    with database() as conn:
        assert conn.execute('SELECT ref FROM activities WHERE repo_id = 30000002').fetchall() == [('gap',)]


# ---------------------------------------------------------------- README

def readme_routes(state):
    """state['etag'], state['text']를 바꾸면 GitHub의 README가 바뀐 것처럼 응답한다."""
    def respond(sent):
        if sent == state['etag']:
            return 304, state['etag'], None
        return gh_readme(state['text'], state['etag'])(sent)
    return {f'{REPO}/readme': respond}


def test_default_branch_push_updates_readme_with_conditional_requests(database):
    state = {'etag': '"v1"', 'text': '# 첫 README\n'}
    github = FakeGitHub(readme_routes(state))
    processor = EventProcessor(database, github)
    send(processor, 'installation.created')
    assert send(processor, 'push') == 'PROCESSED'
    assert readmes(database)[30000001] == ('README.md', '# 첫 README\n', '"v1"')

    with database() as conn:
        conn.execute("UPDATE repo_readmes SET fetched_at = '2026-01-01T00:00:00Z'")
    assert send(processor, 'push') == 'PROCESSED'                   # README는 그대로 (304)
    with database() as conn:
        assert conn.execute("SELECT fetched_at > '2026-01-01T00:00:00Z' FROM repo_readmes").fetchone() == (True,)
    assert readmes(database)[30000001][1] == '# 첫 README\n'

    state.update(etag='"v2"', text='# 고친 README\n')
    assert send(processor, 'push') == 'PROCESSED'
    assert readmes(database)[30000001] == ('README.md', '# 고친 README\n', '"v2"')
    assert github.etags == [None, '"v1"', '"v1"']


def test_other_branch_push_and_branch_delete_do_not_fetch_readme(database):
    github = FakeGitHub(readme_routes({'etag': '"v1"', 'text': '# README\n'}))
    processor = EventProcessor(database, github)
    send(processor, 'installation.created')
    other = fixture('push')
    other['ref'] = 'refs/heads/feature'
    deleted = fixture('push')
    deleted.update(deleted=True, commits=[])
    send(processor, 'push', payload=other)
    send(processor, 'push', payload=deleted)
    assert github.calls == [] and readmes(database) == {}


@pytest.mark.parametrize('response, reason', [
    (lambda sent: GitHubError(status=404), 'no README'),
    (gh_readme('a' * (readme.MAX_BYTES + 1)), 'over 512KB'),
    (lambda sent: (200, '"big"', {'path': 'README.md', 'size': readme.MAX_BYTES + 1, 'encoding': 'none',
                                  'content': ''}), 'over 512KB without content'),
    (gh_readme(b'\xff\xfe broken'), 'not UTF-8'),
    (gh_readme('nul \x00 inside'), 'NUL character'),
])
def test_missing_large_or_invalid_readme_removes_the_stored_one(database, response, reason):
    github = FakeGitHub(readme_routes({'etag': '"v1"', 'text': '# README\n'}))
    processor = EventProcessor(database, github)
    send(processor, 'installation.created')
    send(processor, 'push')
    assert 30000001 in readmes(database)
    github.routes[f'{REPO}/readme'] = response
    assert send(processor, 'push') == 'PROCESSED'
    assert readmes(database) == {}, reason


def test_readme_of_exactly_512kb_is_stored(database):
    github = FakeGitHub({f'{REPO}/readme': gh_readme('a' * readme.MAX_BYTES)})
    processor = EventProcessor(database, github)
    send(processor, 'installation.created')
    send(processor, 'push')
    assert len(readmes(database)[30000001][1]) == readme.MAX_BYTES


def test_readme_is_removed_with_the_repo_but_kept_while_suspended(database):
    github = FakeGitHub({f'{REPO}/readme': gh_readme('# Public\n'), f'{PRIVATE_REPO}/readme': gh_readme('# Private\n')})
    processor = EventProcessor(database, github)
    send(processor, 'installation.created')
    backfill.run_once(database, github, days=0, budget_seconds=30)
    assert list(readmes(database)) == [30000001, 30000002]
    send(processor, 'installation_repositories.removed')
    assert list(readmes(database)) == [30000001]
    send(processor, 'installation.suspend')
    assert list(readmes(database)) == [30000001]
    send(processor, 'installation.unsuspend')
    send(processor, 'installation.deleted')
    assert readmes(database) == {}


def test_readme_failure_on_push_keeps_the_commits_and_the_message(database):
    github = FakeGitHub({f'{REPO}/readme': gh_readme('# README\n')})
    processor = EventProcessor(database, github)
    send(processor, 'installation.created')
    send(processor, 'push')
    github.routes[f'{REPO}/readme'] = lambda sent: GitHubError(status=502)
    assert send(processor, 'push', 'push-while-github-down') == 'PROCESSED'
    with database() as conn:
        assert conn.execute("SELECT status FROM processed_deliveries WHERE delivery_id = 'push-while-github-down'"
                            ).fetchone() == ('PROCESSED',)
    assert readmes(database)[30000001][1] == '# README\n'
    assert len(activities(database, 'COMMIT')) == 2
    assert send(EventProcessor(database), 'push') == 'PROCESSED'    # GitHub 연동이 꺼져 있어도 커밋은 저장


def test_readme_is_not_stored_for_a_repo_removed_meanwhile(database):
    github = FakeGitHub({f'{REPO}/readme': gh_readme('# README\n')})
    send(EventProcessor(database), 'installation.created')
    with database() as conn:
        conn.execute("UPDATE repos SET status = 'UNLINKED' WHERE repo_id = 30000001")
        assert readme.refresh(conn, github, 30000001) == 'SKIPPED'
    assert github.calls == [] and readmes(database) == {}

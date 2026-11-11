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
# 2026URP docs/contracts/db-schema.sql (V1~V8 적용 결과). RepoScope 추가분은 V9를 이어서 적용해 비교한다.
CONTRACT = HERE / 'contract' / 'db-schema.sql'
REPOSCOPE_ADDITIONS = ['V9__reposcope_additions.sql']
MIGRATION_COUNT = 9


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


@pytest.mark.parametrize('name', ['push', 'pull_request.opened', 'issues.opened'])
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

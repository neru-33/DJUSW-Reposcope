import hashlib

import psycopg
from psycopg import sql

from .config import MIGRATIONS


def connect(settings):
    # search_path에는 INGEST_SCHEMA 하나만 둔다. capstone 스키마는 보지 않는다.
    return psycopg.connect(**settings.db, autocommit=True, connect_timeout=5,
                           options='-c statement_timeout=15000 -c lock_timeout=10000 '
                                   f'-c search_path={settings.schema}')


def ensure_schema(conn, name):
    # 스키마가 이미 있으면 DB의 CREATE 권한 없이도 시작할 수 있도록 먼저 확인한다.
    if conn.execute('SELECT 1 FROM pg_namespace WHERE nspname = %s', (name,)).fetchone():
        return
    conn.execute(sql.SQL('CREATE SCHEMA IF NOT EXISTS {}').format(sql.Identifier(name)))


def migrate(conn, directory=MIGRATIONS):
    """Serialize startup and reject edits to an already-applied migration."""
    with conn.transaction():
        conn.execute('SELECT pg_advisory_xact_lock(%s)', (732014513108,))
        conn.execute('''CREATE TABLE IF NOT EXISTS worker_schema_migrations (
            version text PRIMARY KEY, checksum text NOT NULL,
            applied_at timestamptz NOT NULL DEFAULT now())''')
        applied = dict(conn.execute('SELECT version, checksum FROM worker_schema_migrations'))
        files = sorted(directory.glob('V[0-9]*__*.sql'),
                       key=lambda path: int(path.name.split('__')[0][1:]))
        if not files:
            raise RuntimeError('No worker migrations found')
        if set(applied) - {path.name for path in files}:
            raise RuntimeError('An applied migration is missing')
        for path in files:
            content = path.read_text(encoding='utf-8')
            checksum = hashlib.sha256(content.encode('utf-8')).hexdigest()
            if path.name in applied:
                if applied[path.name] != checksum:
                    raise RuntimeError('Applied migration checksum mismatch')
                continue
            conn.execute(content, prepare=False)
            conn.execute('INSERT INTO worker_schema_migrations(version, checksum) VALUES (%s, %s)',
                         (path.name, checksum))

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA_NAME = re.compile(r'[a-z_][a-z0-9_]{0,62}')


def ingest_schema():
    """수집·계정 테이블을 두는 스키마. capstone 스키마와 같을 수 없다."""
    name = os.environ.get('INGEST_SCHEMA', '').strip() or 'public'
    capstone = os.environ.get('CAPSTONE_SCHEMA', '').strip() or 'capstone'
    if not SCHEMA_NAME.fullmatch(name):
        raise ValueError('Invalid INGEST_SCHEMA')
    if name == capstone:
        raise ValueError('INGEST_SCHEMA must differ from CAPSTONE_SCHEMA')
    return name


@dataclass(frozen=True)
class Settings:
    db: dict = field(repr=False)
    queue_url: str
    raw_bucket: str
    region: str
    app_id: str
    app_key_file: str
    schema: str = 'public'

    @classmethod
    def from_env(cls):
        names = ('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD',
                 'DB_SSLMODE', 'QUEUE_URL', 'RAW_BUCKET', 'AWS_REGION')
        missing = [name for name in names if not os.environ.get(name)]
        if missing:
            raise ValueError('Missing configuration: ' + ', '.join(missing))
        db = {key: os.environ[name] for key, name in (
            ('host', 'DB_HOST'), ('port', 'DB_PORT'), ('dbname', 'DB_NAME'),
            ('user', 'DB_USER'), ('password', 'DB_PASSWORD'), ('sslmode', 'DB_SSLMODE'))}
        return cls(db, os.environ['QUEUE_URL'], os.environ['RAW_BUCKET'], os.environ['AWS_REGION'],
                   os.environ.get('GITHUB_APP_ID', ''),
                   os.environ.get('GITHUB_APP_PRIVATE_KEY_FILE', '/run/secrets/github-app-key'),
                   schema=ingest_schema())


MIGRATIONS = Path(__file__).resolve().parents[1] / 'migrations'

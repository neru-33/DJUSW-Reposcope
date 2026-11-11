import boto3
import pytest

from backend.worker.config import Settings

REQUIRED = ('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'DB_SSLMODE',
            'QUEUE_URL', 'RAW_BUCKET', 'AWS_REGION')


def test_runtime_region_and_credentials_are_not_hardcoded(monkeypatch):
    values = {'DB_HOST': 'db-host', 'DB_PORT': '5432', 'DB_NAME': 'db', 'DB_USER': 'user',
              'DB_PASSWORD': 'do-not-log-this', 'DB_SSLMODE': 'require',
              'QUEUE_URL': 'queue', 'RAW_BUCKET': 'bucket', 'AWS_REGION': 'eu-west-1',
              'AWS_ACCESS_KEY_ID': 'test', 'AWS_SECRET_ACCESS_KEY': 'test'}
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    monkeypatch.delenv('AWS_DEFAULT_REGION', raising=False)
    settings = Settings.from_env()
    client = boto3.Session(region_name=settings.region).client('sqs')
    assert client.meta.region_name == 'eu-west-1'
    assert settings.db['sslmode'] == 'require'
    assert values['DB_PASSWORD'] not in repr(settings)


def test_missing_configuration_error_never_includes_values(monkeypatch):
    monkeypatch.delenv('DB_HOST', raising=False)
    monkeypatch.setenv('DB_PASSWORD', 'secret-value')
    with pytest.raises(ValueError, match='DB_HOST') as error:
        Settings.from_env()
    assert 'secret-value' not in str(error.value)


@pytest.fixture
def required(monkeypatch):
    for name in REQUIRED:
        monkeypatch.setenv(name, 'x')
    monkeypatch.delenv('INGEST_SCHEMA', raising=False)
    monkeypatch.delenv('CAPSTONE_SCHEMA', raising=False)
    return monkeypatch


@pytest.mark.parametrize('value, expected', [(None, 'public'), ('', 'public'), ('ingest', 'ingest'),
                                             ('reposcope_ingest', 'reposcope_ingest')])
def test_ingest_schema_defaults_to_public(required, value, expected):
    if value is not None:
        required.setenv('INGEST_SCHEMA', value)
    assert Settings.from_env().schema == expected


@pytest.mark.parametrize('value', ['bad-name', 'Public', '1abc', 'a; DROP TABLE x', 'a' * 64])
def test_invalid_ingest_schema_is_rejected(required, value):
    required.setenv('INGEST_SCHEMA', value)
    with pytest.raises(ValueError, match='INGEST_SCHEMA'):
        Settings.from_env()


@pytest.mark.parametrize('capstone, ingest', [(None, 'capstone'), ('ops', 'ops')])
def test_ingest_schema_cannot_be_the_capstone_schema(required, capstone, ingest):
    if capstone is not None:
        required.setenv('CAPSTONE_SCHEMA', capstone)
    required.setenv('INGEST_SCHEMA', ingest)
    with pytest.raises(ValueError, match='CAPSTONE_SCHEMA'):
        Settings.from_env()

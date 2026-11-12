import re
from dataclasses import dataclass
from datetime import datetime, timezone


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError('Expected timestamp')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Timestamp must include timezone')
    return result.astimezone(timezone.utc)


@dataclass(frozen=True)
class Message:
    delivery_id: str
    event: str
    s3_key: str
    received_at: datetime

    @classmethod
    def parse(cls, body):
        if not isinstance(body, dict) or type(body.get('schema_version')) is not int or body['schema_version'] != 1:
            raise ValueError('Unsupported message schema')
        if body.get('source') not in ('webhook', 'reconcile', 'backfill'):
            raise ValueError('Unsupported source')
        if body.get('event') not in ('installation', 'installation_repositories', 'push', 'pull_request', 'issues'):
            raise ValueError('Unsupported event')
        delivery = body.get('delivery_id')
        key = body.get('s3_key')
        if not isinstance(delivery, str) or not re.fullmatch(r'[A-Za-z0-9-]{1,64}', delivery):
            raise ValueError('Invalid delivery ID')
        if not isinstance(key, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}/[^/]+\.json', key):
            raise ValueError('Invalid raw key')
        return cls(delivery, body['event'], key, timestamp(body.get('received_at')))

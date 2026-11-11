import json
from datetime import datetime, timezone


def emit(event, **fields):
    # Callers supply identifiers/error types only, never payloads or exception text.
    print(json.dumps({'at': datetime.now(timezone.utc).isoformat(),
                      'event': event, **fields}), flush=True)

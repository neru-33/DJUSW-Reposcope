import os
import signal
import threading

import boto3
import psycopg
from botocore.config import Config

from .config import Settings
from .db import connect, ensure_schema, migrate
from .events import EventProcessor
from .github import GitHubApp
from .log import emit
from .queue import Worker


class ShutdownRequested(BaseException):
    pass


def shutdown(signum, frame):
    # Unwind open transactions; unacknowledged messages become visible again.
    raise ShutdownRequested()


def main():
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    retry = threading.Event()
    try:
        settings = Settings.from_env()
        while True:
            try:
                with connect(settings) as conn:
                    ensure_schema(conn, settings.schema)
                    migrate(conn)
                break
            except psycopg.OperationalError as error:
                emit('database_not_ready', error_type=type(error).__name__)
                retry.wait(3)
        github = GitHubApp.from_file(settings.app_id, settings.app_key_file)
        config = Config(connect_timeout=5, read_timeout=25, retries={'total_max_attempts': 2})
        session = boto3.Session(region_name=settings.region)
        worker = Worker(session.client('sqs', config=config), session.client('s3', config=config),
                        settings.queue_url, settings.raw_bucket,
                        EventProcessor(lambda: connect(settings), github))
        emit('worker_started', version=os.environ.get('APP_VERSION', 'unknown'), schema=settings.schema)
        while True:
            try:
                worker.poll()
            except Exception as error:
                emit('poll_failed', error_type=type(error).__name__)
                retry.wait(3)
    except ShutdownRequested:
        emit('worker_stopped')
    except Exception as error:
        emit('startup_failed', error_type=type(error).__name__)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

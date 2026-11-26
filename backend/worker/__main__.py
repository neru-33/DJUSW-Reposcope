import os
import signal
import threading

import boto3
import psycopg
from botocore.config import Config

from . import backfill
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


def backfill_loop(settings, github, stop):
    """과거 기록 가져오기는 별도 스레드에서 (큰 저장소를 읽는 동안에도 webhook 처리는 계속된다)."""
    while not stop.is_set():
        try:
            done = backfill.run_once(lambda: connect(settings), github, settings.backfill_days, budget_seconds=60)
        except Exception as error:
            emit('backfill_error', error_type=type(error).__name__)
            done = 0
        stop.wait(5 if done else 20)


def main():
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    retry = threading.Event()
    stop = threading.Event()
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
        backfill_on = github is not None and settings.backfill_enabled
        emit('worker_started', version=os.environ.get('APP_VERSION', 'unknown'), schema=settings.schema,
             backfill=(settings.backfill_days or 'all') if backfill_on else 'disabled')
        if backfill_on:
            threading.Thread(target=backfill_loop, args=(settings, github, stop), daemon=True).start()
        while True:
            try:
                worker.poll()
            except Exception as error:
                emit('poll_failed', error_type=type(error).__name__)
                retry.wait(3)
    except ShutdownRequested:
        stop.set()
        emit('worker_stopped')
    except Exception as error:
        emit('startup_failed', error_type=type(error).__name__)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

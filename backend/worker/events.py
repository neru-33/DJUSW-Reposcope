from . import activities, readme
from .github import GitHubError
from .log import emit
from .message import timestamp


def identifier(value):
    if type(value) is not int or value <= 0:
        raise ValueError('Expected positive GitHub ID')
    return value


class EventProcessor:
    def __init__(self, connection_factory, github=None):
        self.connection_factory = connection_factory
        self.github = github

    def process(self, message, payload):
        if not isinstance(payload, dict):
            raise ValueError('Expected webhook object')
        with self.connection_factory() as conn, conn.transaction():
            # The unique constraint also serializes concurrent copies of a delivery.
            claimed = conn.execute('''
                INSERT INTO processed_deliveries(delivery_id, event, received_at, status)
                VALUES (%s, %s, %s, 'PROCESSED')
                ON CONFLICT (delivery_id) DO UPDATE SET status = 'PROCESSED', processed_at = NULL
                WHERE processed_deliveries.status = 'FAILED'
                RETURNING delivery_id''',
                (message.delivery_id, message.event, message.received_at)).fetchone()
            if not claimed:
                return 'DUPLICATE'
            status = self._apply(conn, message, payload)
            conn.execute('''UPDATE processed_deliveries SET status = %s, processed_at = now()
                            WHERE delivery_id = %s''', (status, message.delivery_id))
        if message.event == 'push' and status == 'PROCESSED' and readme.default_branch_push(payload):
            self._refresh_readme(activities.repo_id(payload))
        return status

    def _refresh_readme(self, rid):
        """커밋을 저장한 뒤 트랜잭션 밖에서 README를 다시 가져온다. 실패해도 메시지는 실패시키지 않고 다음 push를 기다린다."""
        if self.github is None:
            emit('readme_skipped', repo_id=rid, reason='github_disabled')
            return
        try:
            with self.connection_factory() as conn:
                readme.refresh(conn, self.github, rid)
        except GitHubError as error:
            emit('readme_failed', repo_id=rid, status=error.status)
        except Exception as error:
            emit('readme_failed', repo_id=rid, error_type=type(error).__name__)

    def _apply(self, conn, message, payload):
        action = payload.get('action')
        if message.event == 'installation':
            if action not in ('created', 'deleted', 'suspend', 'unsuspend'):
                return 'IGNORED'
        elif message.event == 'installation_repositories':
            if action not in ('added', 'removed'):
                return 'IGNORED'
        elif message.event in ('push', 'pull_request', 'pull_request_review', 'issues'):
            return activities.apply(conn, message.event, payload)
        else:
            return 'IGNORED'

        installation = payload['installation']
        iid = identifier(installation['id'])
        # Different deliveries for one installation must not interleave their writes.
        conn.execute('SELECT pg_advisory_xact_lock(%s)', (iid,))
        account = installation['account']
        state = {'deleted': 'REMOVED', 'suspend': 'SUSPENDED', 'unsuspend': 'ACTIVE'}.get(action)
        state = state or ('SUSPENDED' if installation.get('suspended_at') else 'ACTIVE')
        selection = payload.get('repository_selection', installation['repository_selection'])
        # A late "created" or repo-change event must not undo a lifecycle change.
        preserve_state = message.event != 'installation' or action == 'created'
        row = conn.execute('''
            INSERT INTO installations(installation_id, account_login, account_id, account_type,
                                      repo_selection, status, installed_at, removed_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (installation_id) DO UPDATE SET
                account_login = EXCLUDED.account_login, account_id = EXCLUDED.account_id,
                account_type = EXCLUDED.account_type, repo_selection = EXCLUDED.repo_selection,
                status = CASE WHEN installations.status = 'REMOVED' OR %s
                              THEN installations.status ELSE EXCLUDED.status END,
                removed_at = COALESCE(installations.removed_at, EXCLUDED.removed_at)
            RETURNING status''',
            (iid, account['login'], identifier(account['id']), account['type'], selection, state,
             timestamp(installation['created_at']), message.received_at if action == 'deleted' else None,
             preserve_state)).fetchone()
        if row[0] == 'REMOVED':
            removed = conn.execute("UPDATE repos SET status = 'UNLINKED', last_synced_at = %s "
                                   'WHERE installation_id = %s RETURNING repo_id', (message.received_at, iid))
            self._removed(conn, [r[0] for r in removed])
            return 'PROCESSED'

        if message.event == 'installation' and action == 'created':
            repos = payload.get('repositories', [])
            if selection == 'all':
                if self.github is not None:
                    repos = self.github.repositories(iid)
                else:
                    emit('all_repositories_lookup_skipped', installation_id=iid, reason='github_disabled')
            for repo in repos:
                self._repo(conn, iid, repo, 'LINKED', message.received_at, initial=True)
        elif message.event == 'installation_repositories':
            for repo in payload.get('repositories_added', []):
                self._repo(conn, iid, repo, 'LINKED', message.received_at)
            for repo in payload.get('repositories_removed', []):
                if self._repo(conn, iid, repo, 'UNLINKED', message.received_at) == 'UNLINKED':
                    self._removed(conn, [repo['id']])
        return 'PROCESSED'

    @staticmethod
    def _removed(conn, repo_ids):
        """설치에서 빠진 저장소: README를 지우고, 다시 추가되면 과거 기록을 처음부터 가져오도록 작업을 SKIPPED로 둔다."""
        if not repo_ids:
            return
        conn.execute('DELETE FROM repo_readmes WHERE repo_id = ANY(%s)', (repo_ids,))
        conn.execute('''UPDATE backfill_jobs SET status = 'SKIPPED', items = NULL, last_error = 'not_linked',
                               finished_at = now()
                        WHERE repo_id = ANY(%s)''', (repo_ids,))

    @staticmethod
    def _repo(conn, iid, repo, state, received_at, initial=False):
        """저장소 행을 맞춘다. 반영한 상태를 돌려주고, 늦게 도착해 반영하지 않았으면 None."""
        if type(repo['private']) is not bool:
            raise ValueError('Expected private flag')
        row = conn.execute('''
            INSERT INTO repos(repo_id, installation_id, full_name, is_private, status, last_synced_at)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (repo_id) DO UPDATE SET
                installation_id = EXCLUDED.installation_id, full_name = EXCLUDED.full_name,
                is_private = EXCLUDED.is_private,
                status = CASE WHEN %s AND repos.installation_id = EXCLUDED.installation_id
                              THEN repos.status ELSE EXCLUDED.status END,
                last_synced_at = EXCLUDED.last_synced_at
            WHERE repos.last_synced_at IS NULL OR repos.last_synced_at <= EXCLUDED.last_synced_at
            RETURNING status''',
            (identifier(repo['id']), iid, repo['full_name'], repo['private'], state, received_at, initial)).fetchone()
        return row[0] if row else None

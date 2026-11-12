from . import activities
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
        return status

    def _apply(self, conn, message, payload):
        action = payload.get('action')
        if message.event == 'installation':
            if action not in ('created', 'deleted', 'suspend', 'unsuspend'):
                return 'IGNORED'
        elif message.event == 'installation_repositories':
            if action not in ('added', 'removed'):
                return 'IGNORED'
        elif message.event in ('push', 'pull_request', 'issues'):
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
            conn.execute("UPDATE repos SET status = 'UNLINKED', last_synced_at = %s WHERE installation_id = %s",
                         (message.received_at, iid))
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
                self._repo(conn, iid, repo, 'UNLINKED', message.received_at)
        return 'PROCESSED'

    @staticmethod
    def _repo(conn, iid, repo, state, received_at, initial=False):
        if type(repo['private']) is not bool:
            raise ValueError('Expected private flag')
        conn.execute('''
            INSERT INTO repos(repo_id, installation_id, full_name, is_private, status, last_synced_at)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (repo_id) DO UPDATE SET
                installation_id = EXCLUDED.installation_id, full_name = EXCLUDED.full_name,
                is_private = EXCLUDED.is_private,
                status = CASE WHEN %s AND repos.installation_id = EXCLUDED.installation_id
                              THEN repos.status ELSE EXCLUDED.status END,
                last_synced_at = EXCLUDED.last_synced_at
            WHERE repos.last_synced_at IS NULL OR repos.last_synced_at <= EXCLUDED.last_synced_at''',
            (identifier(repo['id']), iid, repo['full_name'], repo['private'], state, received_at, initial))

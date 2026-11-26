"""저장소 README 가져오기 (기능 명세 S-2, S-3).

저장소가 연결될 때(backfill.py)와 기본 브랜치에 push가 올 때(events.py) GitHub의 README API를 부른다.
저장한 ETag로 조건부 요청을 보내 바뀌지 않았으면(304) 본문을 다시 받지 않는다.

- 512KB 이하이고 NUL 문자가 없는 UTF-8 README만 저장한다.
- README가 없거나(404) 512KB를 넘거나 UTF-8이 아니면 저장한 README를 지운다.
- 저장소가 설치에서 빠질 때의 삭제는 events.py가 한다.
"""

import base64
import binascii
from urllib.parse import quote

from .github import GitHubError

MAX_BYTES = 512 * 1024


def default_branch_push(payload):
    """기본 브랜치에 대한 push인지. 브랜치 삭제는 아니다."""
    repo = payload.get('repository')
    branch = repo.get('default_branch') if isinstance(repo, dict) else None
    return (isinstance(branch, str) and bool(branch) and not payload.get('deleted')
            and payload.get('ref') == 'refs/heads/' + branch)


def refresh(conn, github, repo_id):
    """README를 다시 가져와 repo_readmes를 맞춘다. 결과 코드를 돌려준다. GitHub 오류는 GitHubError로 올린다."""
    row = conn.execute('''SELECT r.full_name, r.installation_id, r.status, m.etag
                          FROM repos r LEFT JOIN repo_readmes m ON m.repo_id = r.repo_id
                          WHERE r.repo_id = %s''', (repo_id,)).fetchone()
    if not row or row[2] != 'LINKED':
        return 'SKIPPED'
    full_name, installation_id, _, etag = row
    token = github.installation_token(installation_id)
    try:
        status, new_etag, body = github.conditional_get(
            '/repos/' + quote(full_name, safe='/') + '/readme', token, etag)
    except GitHubError as error:
        if error.status == 404:
            _delete(conn, repo_id)
            return 'NOT_FOUND'
        raise
    if status == 304:
        conn.execute('UPDATE repo_readmes SET fetched_at = now() WHERE repo_id = %s', (repo_id,))
        return 'NOT_MODIFIED'

    if not isinstance(body, dict) or not isinstance(body.get('path'), str) or not body['path']:
        raise GitHubError('Invalid README response')
    size = body.get('size')
    if type(size) is int and size > MAX_BYTES:
        _delete(conn, repo_id)
        return 'TOO_LARGE'
    content = _decode(body)
    if content is None:
        _delete(conn, repo_id)
        return 'INVALID'
    if len(content.encode('utf-8')) > MAX_BYTES:
        _delete(conn, repo_id)
        return 'TOO_LARGE'
    with conn.transaction():
        # 가져오는 사이 저장소가 설치에서 빠졌으면 저장하지 않는다
        if not conn.execute("SELECT 1 FROM repos WHERE repo_id = %s AND status = 'LINKED' FOR SHARE",
                            (repo_id,)).fetchone():
            return 'SKIPPED'
        conn.execute('''
            INSERT INTO repo_readmes(repo_id, path, content, etag, fetched_at)
            VALUES (%s, %s, %s, %s, now())
            ON CONFLICT (repo_id) DO UPDATE SET
                path = EXCLUDED.path, content = EXCLUDED.content, etag = EXCLUDED.etag,
                fetched_at = EXCLUDED.fetched_at''',
            (repo_id, body['path'], content, new_etag))
    return 'STORED'


def _decode(body):
    """base64 본문을 UTF-8 문자열로. UTF-8이 아니거나 NUL 문자가 있으면 None."""
    if body.get('encoding') != 'base64' or not isinstance(body.get('content'), str):
        return None
    try:
        raw = base64.b64decode(body['content'])  # GitHub는 60자마다 줄을 바꾼다. 줄바꿈은 무시된다
        text = raw.decode('utf-8')
    except (binascii.Error, ValueError):
        return None
    return None if '\x00' in text else text


def _delete(conn, repo_id):
    conn.execute('DELETE FROM repo_readmes WHERE repo_id = %s', (repo_id,))

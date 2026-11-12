import http.client
import json
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

from .log import emit

API_ROOT = 'https://api.github.com'


class GitHubError(RuntimeError):
    """status: HTTP 상태 코드(없으면 네트워크 오류). 응답 본문은 담지 않는다."""
    def __init__(self, message='GitHub request failed', status=None):
        super().__init__(message)
        self.status = status


class GitHubApp:
    def __init__(self, app_id, private_key, opener=urlopen):
        self.app_id = str(app_id)
        self.private_key = private_key
        self.opener = opener
        self._tokens = {}  # installation_id -> (token, 만료 시각). 토큰은 1시간 유효

    @classmethod
    def from_file(cls, app_id, filename):
        if not app_id:
            emit('github_disabled', reason='missing_app_id')
            return None
        try:
            pem = Path(filename).read_bytes()
            key = serialization.load_pem_private_key(pem, password=None)
            if not isinstance(key, RSAPrivateKey):
                raise ValueError('Expected RSA key')
        except (OSError, ValueError, TypeError):
            emit('github_disabled', reason='missing_or_invalid_key')
            return None
        return cls(app_id, pem)

    def _request(self, method, path, token):
        request = Request(API_ROOT + path, method=method,
                          data=b'{}' if method == 'POST' else None,
                          headers={'Accept': 'application/vnd.github+json',
                                   'Authorization': 'Bearer ' + token,
                                   'X-GitHub-Api-Version': '2026-03-10',
                                   'User-Agent': 'reposcope-worker',
                                   'Content-Type': 'application/json'})
        try:
            with self.opener(request, timeout=10) as response:
                return json.load(response)
        except HTTPError as error:
            # Response bodies and headers may contain tokens/private repo details. Keep the status only.
            raise GitHubError('GitHub request failed', status=error.code) from None
        except (URLError, OSError, http.client.HTTPException, ValueError):
            raise GitHubError('GitHub request failed') from None

    def installation_token(self, installation_id):
        cached = self._tokens.get(installation_id)
        if cached and cached[1] > time.time():
            return cached[0]
        now = int(time.time())
        app_jwt = jwt.encode({'iat': now - 60, 'exp': now + 540, 'iss': self.app_id},
                             self.private_key, algorithm='RS256')
        result = self._request('POST', f'/app/installations/{installation_id}/access_tokens', app_jwt)
        token = result.get('token') if isinstance(result, dict) else None
        if not isinstance(token, str) or not token:
            raise GitHubError('Missing installation token')
        self._tokens[installation_id] = (token, time.time() + 50 * 60)
        return token

    def get(self, path, token):
        return self._request('GET', path, token)

    def repositories(self, installation_id):
        token = self.installation_token(installation_id)
        repositories = []
        page = 1
        while True:
            result = self._request('GET', f'/installation/repositories?per_page=100&page={page}', token)
            batch = result.get('repositories')
            if not isinstance(batch, list):
                raise GitHubError('Invalid repository list')
            repositories.extend(batch)
            if len(batch) < 100:
                return repositories
            page += 1

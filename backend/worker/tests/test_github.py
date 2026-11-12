import io
import json
from urllib.error import HTTPError
from unittest.mock import Mock

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from backend.worker.github import GitHubApp, GitHubError


@pytest.fixture
def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def app(key, opener):
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption())
    return GitHubApp('1234', pem, opener)


def test_installation_token_and_paginated_repository_list(key):
    batches = [{'token': 'installation-secret'}, {'repositories': [{'id': i} for i in range(100)]},
               {'repositories': [{'id': 100}]}]
    opener = Mock(side_effect=[io.BytesIO(json.dumps(body).encode()) for body in batches])
    repos = app(key, opener).repositories(42)
    assert len(repos) == 101
    requests = [call.args[0] for call in opener.call_args_list]
    assert requests[0].full_url == 'https://api.github.com/app/installations/42/access_tokens'
    assert requests[0].method == 'POST'
    signed = requests[0].get_header('Authorization').removeprefix('Bearer ')
    claims = jwt.decode(signed, key.public_key(), algorithms=['RS256'])
    assert claims['iss'] == '1234'
    assert claims['exp'] - claims['iat'] <= 600
    assert requests[1].get_header('Authorization') == 'Bearer installation-secret'
    assert requests[2].full_url.endswith('per_page=100&page=2')


def test_github_errors_do_not_expose_response_bodies(key):
    opener = Mock(side_effect=HTTPError('https://api.github.com', 403, 'private user@example.com', {}, None))
    with pytest.raises(GitHubError, match='^GitHub request failed$'):
        app(key, opener).repositories(42)


def test_missing_or_placeholder_key_disables_only_github(tmp_path):
    path = tmp_path / 'key.pem'
    path.write_text('CHANGE_ME')
    assert GitHubApp.from_file('123', str(path)) is None
    assert GitHubApp.from_file('', str(path)) is None
    assert GitHubApp.from_file('123', str(tmp_path / 'missing')) is None


def test_valid_rsa_file_enables_github(tmp_path, key):
    path = tmp_path / 'key.pem'
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))
    assert isinstance(GitHubApp.from_file('123', str(path)), GitHubApp)


def test_installation_token_is_cached_and_get_uses_it(key):
    batches = [{'token': 'installation-secret'}, [{'sha': 'a'}], [{'sha': 'b'}]]
    opener = Mock(side_effect=[io.BytesIO(json.dumps(body).encode()) for body in batches])
    github = app(key, opener)
    assert github.get('/repos/o/r/commits?page=1', github.installation_token(42)) == [{'sha': 'a'}]
    assert github.get('/repos/o/r/commits?page=2', github.installation_token(42)) == [{'sha': 'b'}]
    requests = [call.args[0] for call in opener.call_args_list]
    assert [r.method for r in requests] == ['POST', 'GET', 'GET']  # 토큰은 한 번만 만든다
    assert requests[1].full_url == 'https://api.github.com/repos/o/r/commits?page=1'
    assert requests[2].get_header('Authorization') == 'Bearer installation-secret'
    assert 'eclipes' not in requests[1].get_header('User-agent')


def test_http_status_is_kept_but_not_the_body(key):
    opener = Mock(side_effect=HTTPError('https://api.github.com', 404, 'private user@example.com', {}, None))
    with pytest.raises(GitHubError, match='^GitHub request failed$') as error:
        app(key, opener).get('/repos/o/r/commits', 'token')
    assert error.value.status == 404 and 'user@example.com' not in str(error.value)


def test_connection_dropped_mid_response_is_a_github_error(key):
    import http.client
    opener = Mock(side_effect=http.client.RemoteDisconnected('closed'))
    with pytest.raises(GitHubError) as error:
        app(key, opener).get('/repos/o/r/commits', 'token')
    assert error.value.status is None

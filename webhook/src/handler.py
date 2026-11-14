"""RepoScope Webhook 수신 Lambda (기능 명세 S-1).

X-Hub-Signature-256 검증 → 원본 S3 저장 → SQS에 S3 위치 전송 → 바로 2xx 응답.
서명이 틀린 요청은 어디에도 저장하지 않고 401로 응답한다. DB에는 접근하지 않는다.
"""

import base64
import hashlib
import hmac
import json
import os
import re
import time
from datetime import datetime, timezone

SCHEMA_VERSION = 1
# 큐로 넘기는 이벤트. worker가 처리하는 이벤트와 같아야 한다. 그 밖의 이벤트(ping 등)는 서명만 확인하고 저장하지 않는다.
# pull_request_review는 worker가 처리하게 되면 추가한다(T-23).
QUEUED_EVENTS = frozenset({"installation", "installation_repositories", "push", "pull_request", "issues"})
SECRET_TTL_SECONDS = 300
PLACEHOLDER_SECRET = b"CHANGE_ME"
MIN_SECRET_LENGTH = 32

_DELIVERY_ID_RE = re.compile(r"^[A-Za-z0-9-]{1,64}$")
_EVENT_RE = re.compile(r"^[a-z_]{1,64}$")

_clients = {}
_secret_cache = {"value": None, "loaded_at": 0.0}


def _client(name):
    if name not in _clients:
        import boto3  # Lambda 런타임에 들어 있다. 테스트에서는 가짜 클라이언트를 넣는다.

        _clients[name] = boto3.client(name)
    return _clients[name]


def _webhook_secret():
    now = time.monotonic()
    if _secret_cache["value"] is None or now - _secret_cache["loaded_at"] > SECRET_TTL_SECONDS:
        resp = _client("ssm").get_parameter(Name=os.environ["WEBHOOK_SECRET_PARAM"], WithDecryption=True)
        _secret_cache["value"] = resp["Parameter"]["Value"].encode("utf-8")
        _secret_cache["loaded_at"] = now
    return _secret_cache["value"]


def _secret_is_configured(secret):
    # 초기값 CHANGE_ME는 저장소에 공개돼 있으므로, 그 값으로 서명한 요청이 통과하지 않게 막는다.
    return secret != PLACEHOLDER_SECRET and len(secret) >= MIN_SECRET_LENGTH


def verify_signature(body, signature_header, secret):
    if not signature_header or not signature_header.startswith("sha256="):
        return False
    expected = "sha256=" + hmac.new(secret, body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature_header)


def _response(status, message):
    return {
        "statusCode": status,
        "headers": {"content-type": "application/json"},
        "body": json.dumps({"message": message}),
    }


def _log(**fields):
    # 본문, 서명, 비밀값은 로그에 남기지 않는다.
    print(json.dumps(fields, ensure_ascii=False))


def handler(event, context):
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    raw = event.get("body") or ""
    body = base64.b64decode(raw) if event.get("isBase64Encoded") else raw.encode("utf-8")

    secret = _webhook_secret()
    if not _secret_is_configured(secret):
        _log(outcome="secret_not_configured")
        return _response(503, "webhook secret not configured")

    if not verify_signature(body, headers.get("x-hub-signature-256"), secret):
        _log(outcome="invalid_signature")
        return _response(401, "invalid signature")

    delivery_id = headers.get("x-github-delivery", "")
    gh_event = headers.get("x-github-event", "")
    if not _DELIVERY_ID_RE.match(delivery_id) or not _EVENT_RE.match(gh_event):
        _log(outcome="bad_headers")
        return _response(400, "missing or invalid GitHub headers")

    if gh_event not in QUEUED_EVENTS:
        _log(outcome="ignored", event=gh_event, delivery_id=delivery_id)
        return _response(200, "ignored")

    now = datetime.now(timezone.utc)
    s3_key = f"{now:%Y-%m-%d}/{delivery_id}.json"

    # 보관 기간 규칙은 버킷 전체에 적용되므로 객체 태그를 붙이지 않는다.
    _client("s3").put_object(
        Bucket=os.environ["RAW_BUCKET"],
        Key=s3_key,
        Body=body,
        ContentType="application/json",
    )

    message = {
        "schema_version": SCHEMA_VERSION,
        "source": "webhook",
        "delivery_id": delivery_id,
        "event": gh_event,
        "s3_key": s3_key,
        "received_at": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    _client("sqs").send_message(QueueUrl=os.environ["QUEUE_URL"], MessageBody=json.dumps(message))

    _log(outcome="accepted", event=gh_event, delivery_id=delivery_id, s3_key=s3_key)
    return _response(202, "accepted")

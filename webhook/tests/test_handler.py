"""수신 Lambda 단위 테스트. AWS 없이 저장소 루트에서 실행한다: python -m unittest discover -s webhook/tests"""

import base64
import hashlib
import hmac
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
import handler  # noqa: E402

SECRET = "a" * 64
BODY = json.dumps({"action": "created", "installation": {"id": 1}}).encode()
DELIVERY = "72d3162e-cc78-11e3-81ab-4c9367dc0958"
WORKER_EVENTS = {"installation", "installation_repositories", "push", "pull_request", "issues"}


class FakeSSM:
    def __init__(self, value):
        self.value = value

    def get_parameter(self, Name, WithDecryption):
        return {"Parameter": {"Value": self.value}}


class FakeS3:
    def __init__(self):
        self.puts = []

    def put_object(self, **kwargs):
        self.puts.append(kwargs)


class FakeSQS:
    def __init__(self):
        self.sent = []

    def send_message(self, **kwargs):
        self.sent.append(kwargs)


def sign(body, secret=SECRET):
    return "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


def make_event(body=BODY, signature=None, event="installation", delivery=DELIVERY, b64=False):
    headers = {
        "X-GitHub-Event": event,
        "X-GitHub-Delivery": delivery,
        "X-Hub-Signature-256": signature if signature is not None else sign(body),
    }
    return {
        "headers": headers,
        "body": base64.b64encode(body).decode() if b64 else body.decode(),
        "isBase64Encoded": b64,
    }


class HandlerTest(unittest.TestCase):
    def setUp(self):
        os.environ.update(
            WEBHOOK_SECRET_PARAM="/reposcope/webhook-secret",
            RAW_BUCKET="reposcope-raw-events",
            QUEUE_URL="https://sqs.example/reposcope-events",
        )
        self.s3, self.sqs = FakeS3(), FakeSQS()
        self.use_secret(SECRET)

    def use_secret(self, value):
        handler._clients.clear()
        handler._clients.update(ssm=FakeSSM(value), s3=self.s3, sqs=self.sqs)
        handler._secret_cache.update(value=None, loaded_at=0.0)

    def assert_nothing_stored(self):
        self.assertEqual(self.s3.puts, [])
        self.assertEqual(self.sqs.sent, [])

    def test_valid_request_stores_raw_and_enqueues_pointer(self):
        resp = handler.handler(make_event(), None)

        self.assertEqual(resp["statusCode"], 202)
        self.assertEqual(len(self.s3.puts), 1)
        put = self.s3.puts[0]
        self.assertEqual(put["Bucket"], "reposcope-raw-events")
        self.assertEqual(put["Body"], BODY)
        self.assertRegex(put["Key"], rf"^\d{{4}}-\d{{2}}-\d{{2}}/{DELIVERY}\.json$")

        self.assertEqual(self.sqs.sent[0]["QueueUrl"], "https://sqs.example/reposcope-events")
        msg = json.loads(self.sqs.sent[0]["MessageBody"])
        self.assertEqual(set(msg), {"schema_version", "source", "delivery_id", "event", "s3_key", "received_at"})
        self.assertEqual(msg["schema_version"], 1)
        self.assertEqual(msg["source"], "webhook")
        self.assertEqual(msg["delivery_id"], DELIVERY)
        self.assertEqual(msg["event"], "installation")
        self.assertEqual(msg["s3_key"], put["Key"])
        self.assertRegex(msg["received_at"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

    def test_raw_object_has_no_tags(self):
        # Lambda 역할에는 s3:PutObjectTagging 권한이 없어 태그를 붙이면 AWS에서 거부된다.
        handler.handler(make_event(), None)
        self.assertNotIn("Tagging", self.s3.puts[0])

    def test_every_worker_event_is_queued(self):
        for gh_event in sorted(WORKER_EVENTS):
            with self.subTest(event=gh_event):
                resp = handler.handler(make_event(event=gh_event), None)
                self.assertEqual(resp["statusCode"], 202)
        self.assertEqual({json.loads(m["MessageBody"])["event"] for m in self.sqs.sent}, WORKER_EVENTS)

    def test_pull_request_review_is_not_queued_yet(self):
        resp = handler.handler(make_event(event="pull_request_review"), None)
        self.assertEqual(resp["statusCode"], 200)
        self.assert_nothing_stored()

    def test_base64_body_is_verified_on_raw_bytes(self):
        resp = handler.handler(make_event(b64=True), None)
        self.assertEqual(resp["statusCode"], 202)
        self.assertEqual(self.s3.puts[0]["Body"], BODY)

    def test_wrong_signature_is_401_and_stores_nothing(self):
        resp = handler.handler(make_event(signature=sign(BODY, "wrong-secret")), None)
        self.assertEqual(resp["statusCode"], 401)
        self.assert_nothing_stored()

    def test_missing_signature_is_401(self):
        resp = handler.handler(make_event(signature=""), None)
        self.assertEqual(resp["statusCode"], 401)
        self.assert_nothing_stored()

    def test_tampered_body_is_401(self):
        event = make_event()
        event["body"] = event["body"].replace("created", "deleted")
        resp = handler.handler(event, None)
        self.assertEqual(resp["statusCode"], 401)
        self.assert_nothing_stored()

    def test_placeholder_secret_rejects_even_correctly_signed_request(self):
        self.use_secret("CHANGE_ME")
        resp = handler.handler(make_event(signature=sign(BODY, "CHANGE_ME")), None)
        self.assertEqual(resp["statusCode"], 503)
        self.assert_nothing_stored()

    def test_short_secret_is_rejected(self):
        self.use_secret("short")
        resp = handler.handler(make_event(signature=sign(BODY, "short")), None)
        self.assertEqual(resp["statusCode"], 503)
        self.assert_nothing_stored()

    def test_ping_is_acknowledged_without_storing(self):
        resp = handler.handler(make_event(event="ping"), None)
        self.assertEqual(resp["statusCode"], 200)
        self.assert_nothing_stored()

    def test_path_like_delivery_id_is_rejected(self):
        resp = handler.handler(make_event(delivery="../deploy/compose"), None)
        self.assertEqual(resp["statusCode"], 400)
        self.assert_nothing_stored()

    def test_missing_event_header_is_rejected(self):
        resp = handler.handler(make_event(event=""), None)
        self.assertEqual(resp["statusCode"], 400)
        self.assert_nothing_stored()


if __name__ == "__main__":
    unittest.main()

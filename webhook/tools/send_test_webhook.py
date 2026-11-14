"""수신부에 서명된 테스트 요청을 보낸다. 로컬 ingest 서비스와 배포한 API Gateway 모두에 쓸 수 있다.

사용:
  python webhook/tools/send_test_webhook.py <webhook_url> <payload.json> [--event installation] [--delivery <id>]

서명 비밀값은 환경 변수 WEBHOOK_SECRET에서 읽는다. 명령줄에 쓰면 셸 기록에 남기 때문이다.
같은 --delivery로 두 번 보내면 재전송(중복 처리) 시험이 된다. --bad-signature는 틀린 서명으로 보낸다.
"""

import argparse
import hashlib
import hmac
import os
import sys
import urllib.error
import urllib.request
import uuid


def main():
    p = argparse.ArgumentParser()
    p.add_argument("url")
    p.add_argument("payload")
    p.add_argument("--event", default="installation")
    p.add_argument("--delivery", default=None)
    p.add_argument("--bad-signature", action="store_true")
    args = p.parse_args()

    secret = os.environ.get("WEBHOOK_SECRET")
    if not secret:
        sys.exit("WEBHOOK_SECRET 환경 변수를 설정한다")

    with open(args.payload, "rb") as f:
        body = f.read()
    delivery = args.delivery or str(uuid.uuid4())
    key = b"wrong-secret" if args.bad_signature else secret.encode()
    signature = "sha256=" + hmac.new(key, body, hashlib.sha256).hexdigest()

    req = urllib.request.Request(
        args.url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-GitHub-Event": args.event,
            "X-GitHub-Delivery": delivery,
            "X-Hub-Signature-256": signature,
            "User-Agent": "reposcope-test-sender",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            print(resp.status, resp.read().decode(), f"delivery={delivery}")
    except urllib.error.HTTPError as e:
        print(e.code, e.read().decode(), f"delivery={delivery}")


if __name__ == "__main__":
    main()

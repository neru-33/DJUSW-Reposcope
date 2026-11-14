"""로컬용 API Gateway 대역. HTTP 요청을 HTTP API(v2) 이벤트로 바꿔 운영과 같은 핸들러를 호출한다."""

import base64
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, "/app/src")
import handler  # noqa: E402  (webhook/src/handler.py)


def _read_body(req):
    if req.headers.get("Transfer-Encoding", "").lower() == "chunked":
        chunks = []
        while True:
            size = int(req.rfile.readline().strip() or b"0", 16)
            if size == 0:
                req.rfile.readline()
                return b"".join(chunks)
            chunks.append(req.rfile.read(size))
            req.rfile.readline()
    return req.rfile.read(int(req.headers.get("Content-Length") or 0))


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, '{"status":"ok"}')
        else:
            self._send(404, '{"message":"not found"}')

    def do_POST(self):
        if self.path != "/webhook":
            self._send(404, '{"message":"not found"}')
            return
        body = _read_body(self)
        event = {
            "version": "2.0",
            "routeKey": "POST /webhook",
            "rawPath": "/webhook",
            "headers": {k.lower(): v for k, v in self.headers.items()},
            "body": base64.b64encode(body).decode(),
            "isBase64Encoded": True,
        }
        try:
            resp = handler.handler(event, None)
        except Exception as e:  # 운영에서는 Lambda 오류가 API Gateway의 500이 된다.
            print(json.dumps({"outcome": "lambda_error", "error": repr(e)}))
            resp = {"statusCode": 500, "body": '{"message":"internal error"}'}
        self._send(resp["statusCode"], resp.get("body", ""))

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    print("ingest listening on :8080  (POST /webhook, GET /health)")
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()

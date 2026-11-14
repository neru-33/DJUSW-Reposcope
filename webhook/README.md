# 수신 Lambda

GitHub App의 Webhook 요청을 받아 서명을 검증하고, 원본 payload를 S3에, S3 위치를 담은 메시지를 SQS에 보낸다(기능 명세 S-1). Python 3.13으로 구현했고 DB에는 접근하지 않는다.

```text
GitHub → API Gateway POST /webhook → Lambda → S3 reposcope-raw-events (원본)
                                            └ SQS reposcope-events → worker
```

## 폴더

| 경로 | 내용 | 배포 |
|---|---|---|
| `src/handler.py` | Lambda 핸들러(`handler.handler`) | 포함 |
| `tests/` | 단위 테스트 | 제외 |
| `local/` | 로컬 수집 구성의 API Gateway 대역 서버와 Dockerfile | 제외 |
| `tools/send_test_webhook.py` | 서명된 테스트 요청 전송 도구 | 제외 |

Terraform은 `src/`만 압축해 배포한다(`infra/envs/prod/main.tf`의 `lambda_source_dir`).

## 처리 규칙

| 요청 | 응답 | 저장 |
|---|---|---|
| 서명이 없거나 틀림 | `401` | 없음 |
| 비밀값이 초기값 `CHANGE_ME`이거나 32자보다 짧음 | `503` | 없음 |
| `X-GitHub-Delivery`, `X-GitHub-Event` 헤더가 없거나 형식이 틀림 | `400` | 없음 |
| 큐로 넘기지 않는 이벤트(`ping`, `pull_request_review` 등) | `200` | 없음 |
| `installation`, `installation_repositories`, `push`, `pull_request`, `issues` | `202` | S3 원본, SQS 메시지 |
| S3 또는 SQS 저장 실패 | `500` | GitHub가 실패로 기록한다 |

- 서명은 API Gateway가 넘겨준 원본 바이트로 검증한다.
- S3 키는 `YYYY-MM-DD/<delivery_id>.json`이다. 원본 버킷의 보관 기간 규칙이 버킷 전체에 적용되므로 객체 태그를 붙이지 않는다.
- SQS 메시지 형식은 `Onezero-GDGoC-DJU/2026URP`의 `docs/contracts/sqs-message.schema.json`과 같다.
- 큐로 넘기는 이벤트는 worker가 처리하는 이벤트와 같아야 한다. `pull_request_review`는 worker가 처리하게 되면 `QUEUED_EVENTS`에 추가한다(T-23).
- 비밀값은 Parameter Store에서 읽고 5분 동안 메모리에 둔다.
- 로그에는 결과, 이벤트, delivery ID만 남기고 본문, 서명, 비밀값은 남기지 않는다.

## 환경 변수

| 변수 | 내용 | 로컬 값 |
|---|---|---|
| `WEBHOOK_SECRET_PARAM` | 서명 비밀값이 든 Parameter Store 이름 | `/reposcope/webhook-secret` |
| `RAW_BUCKET` | 원본 이벤트 버킷 | `reposcope-raw-events` |
| `QUEUE_URL` | 이벤트 큐 주소 | `http://moto:5000/123456789012/reposcope-events` |

AWS에서는 Terraform이 위 값을 설정한다. 로컬에서는 `infra/local/compose.yml`의 `ingest` 서비스에 들어 있다.

## 테스트

저장소 루트에서 실행한다. AWS와 추가 패키지가 필요 없다.

```bash
python -m unittest discover -s webhook/tests
```

## 로컬 실행

수집 구성을 실행하면 `ingest` 서비스가 `http://localhost:9000/webhook`에서 요청을 받는다([로컬 실행 환경](../infra/local/README.md)). 서명 비밀값은 `.env`의 `LOCAL_WEBHOOK_SECRET`이다.

```bash
WEBHOOK_SECRET=<LOCAL_WEBHOOK_SECRET 값> python webhook/tools/send_test_webhook.py \
  http://localhost:9000/webhook backend/worker/tests/fixtures/github/installation.created.json
```

`--event`로 이벤트 이름을, `--delivery`로 delivery ID를 지정한다. 같은 delivery ID로 두 번 보내면 중복 처리를 시험할 수 있다. `--bad-signature`는 틀린 서명으로 보낸다.

## 원본

`Onezero-GDGoC-DJU/2026URP` 커밋 `b183540e9440667278a23268975fddebb40b9515`에서 가져왔다.

| RepoScope 경로 | 원본 경로 | 변경 |
|---|---|---|
| `src/handler.py` | `lambda/ingest/src/handler.py` | S3 객체 태그 제거, `pull_request_review`를 큐 대상에서 제외, 목록 이름을 `QUEUED_EVENTS`로 변경 |
| `tests/test_handler.py` | `lambda/ingest/tests/test_handler.py` | 위 변경에 맞춘 테스트 추가, 자원 이름을 RepoScope 값으로 변경 |
| `local/server.py` | `compose/local/ingest/server.py` | 핸들러 경로 변경 |
| `local/Dockerfile` | `compose/local/ingest/Dockerfile` | moto 초기화는 `moto-init` 서비스가 하므로 제거, 실행 사용자 분리 |
| `tools/send_test_webhook.py` | `lambda/ingest/tools/send_test_webhook.py` | 비밀값을 환경 변수로 받음, `--bad-signature` 추가 |

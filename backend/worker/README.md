# 수집 worker

SQS 메시지를 읽어 S3 원본 payload를 가져오고, 수집 테이블에 멱등 upsert한다(기능 명세 S-2). Python 3.13, boto3, psycopg 3으로 구현했다.

```text
SQS reposcope-events → worker → S3 reposcope-raw-events (원본) → PostgreSQL (INGEST_SCHEMA)
                      └ 5회 실패 → reposcope-events-dlq
```

## 원본

`Onezero-GDGoC-DJU/2026URP` 커밋 `4c778e6b3a81d343cb09adbeb2f24ddae43bcf8f`에서 가져왔다. 메시지 형식은 그 저장소의 `docs/contracts/sqs-message.schema.json`, 테이블은 `docs/contracts/db-schema.sql`과 같다.

| RepoScope 경로 | 원본 경로 | 변경 |
|---|---|---|
| `events.py`, `activities.py`, `message.py`, `queue.py`, `log.py`, `__init__.py`, `requirements.txt`, `Dockerfile` | `backend/worker/` 같은 이름 | 없음 |
| `tests/test_queue.py`, `tests/test_github.py` | `backend/worker/tests/` 같은 이름 | 없음 |
| `tests/fixtures/github/*.json` | `fixtures/github/*.json` | 없음 |
| `tests/contract/db-schema.sql` | `docs/contracts/db-schema.sql` | 없음 |
| `__main__.py` | `backend/worker/__main__.py` | 과거 기록 스레드 제거, 시작 시 `INGEST_SCHEMA` 스키마 생성 |
| `config.py` | `backend/worker/config.py` | `BACKFILL`, `BACKFILL_DAYS` 제거, `INGEST_SCHEMA` 추가 |
| `db.py` | `backend/worker/db.py` | 접속 `search_path`를 `INGEST_SCHEMA`로 지정, `ensure_schema` 추가(스키마가 없을 때만 생성) |
| `github.py` | `backend/worker/github.py` | `User-Agent`를 `reposcope-worker`로 변경 |
| `tests/test_config.py` | `backend/worker/tests/test_config.py` | 과거 기록 테스트 제거, `INGEST_SCHEMA` 테스트 추가 |
| `tests/test_database.py` | `backend/worker/tests/test_database.py` | 과거 기록 테스트 제거, 계약 비교에 V9 포함(컬럼·제약·인덱스), 스키마 분리 테스트 추가 |

과거 기록 가져오기(`backfill.py`)는 가져오지 않았다. T-22에서 추가한다.

## 처리 규칙

- `processed_deliveries`로 같은 `delivery_id`를 한 번만 반영한다. 동시에 들어온 중복도 같은 트랜잭션에서 하나만 처리된다.
- 처리 결과를 DB에 커밋한 뒤에만 SQS 메시지를 삭제한다. 실패하면 삭제하지 않으므로 5회 수신 뒤 DLQ로 이동한다.
- 활동은 `(repo_id, kind, ref)`로 upsert한다. 늦게 도착한 `opened`는 `closed`·`merged`를 되돌리지 않고, `merged`는 최종 상태다.
- `LINKED`가 아닌 저장소의 활동, 태그 push, 브랜치 삭제는 저장하지 않는다. 대상이 아닌 이벤트는 `IGNORED`로 기록하고 메시지를 삭제한다.
- 커밋은 메시지 첫 줄만 저장한다. 이메일, 커밋 본문, PR·이슈 본문, diff는 저장하지 않는다. 로그에는 ID와 오류 종류만 남긴다.
- 처리하는 이벤트는 `installation`, `installation_repositories`, `push`, `pull_request`, `issues`다. 그 밖의 이벤트는 메시지 파싱에서 실패해 DLQ로 간다. 리뷰(`pull_request_review`)와 `parent_ref`, `merged_at` 저장은 T-23에서 추가한다.

## 마이그레이션

시작할 때 `INGEST_SCHEMA` 스키마가 없으면 만들고 `backend/migrations/V*__*.sql`을 번호 순서대로 적용한다. 적용 이력은 같은 스키마의 `worker_schema_migrations`에 파일 이름과 체크섬으로 남는다. 여러 worker가 동시에 시작해도 advisory lock으로 한 번만 적용된다. 이미 적용한 파일을 고치거나 지우면 시작하지 않는다.

worker의 접속 `search_path`에는 `INGEST_SCHEMA`만 있으므로 `capstone` 스키마를 읽거나 쓰지 않는다. `INGEST_SCHEMA`가 `CAPSTONE_SCHEMA`와 같으면 시작하지 않는다.

## 환경 변수

| 변수 | 필수 | 로컬 값 |
|---|:-:|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SSLMODE` | ✔ | compose가 `.env` 값으로 넣는다 |
| `INGEST_SCHEMA` | | 기본 `public`. 영문 소문자, 숫자, `_` |
| `CAPSTONE_SCHEMA` | | 기본 `capstone`. 같은 이름을 막는 데만 쓴다 |
| `QUEUE_URL` | ✔ | `http://moto:5000/123456789012/reposcope-events` |
| `RAW_BUCKET` | ✔ | `reposcope-raw-events` |
| `AWS_REGION` | ✔ | `ap-northeast-2` |
| `AWS_ENDPOINT_URL` | | 로컬만 `http://moto:5000`. AWS에서는 넣지 않는다 |
| `GITHUB_APP_ID` | | 비우면 GitHub 연동을 끈다 |
| `GITHUB_APP_PRIVATE_KEY_FILE` | | 기본 `/run/secrets/github-app-key`. 파일이 없거나 PEM이 아니면 GitHub 연동을 끈다 |
| `APP_VERSION` | | 시작 로그에 남긴다 |

GitHub 연동은 저장소 전체 선택(`all`)으로 설치했을 때 설치 저장소 목록을 조회하는 데 쓴다. 꺼져 있으면 payload에 들어 있는 저장소만 저장한다.

## 실행

수집 구성에 포함되어 있다([로컬 실행 환경](../../infra/local/README.md)).

```bash
docker compose --env-file .env -f infra/local/compose.yml --profile ingest up -d --build
docker compose --env-file .env -f infra/local/compose.yml --profile ingest logs -f worker
```

`worker_started`가 나오면 마이그레이션 적용이 끝난 것이다.

## 테스트

PC에 Python을 설치하지 않고 `python:3.13-slim` 컨테이너에서 실행한다. postgres가 떠 있어야 한다. 테스트마다 임시 스키마를 만들고 지우므로 기존 테이블은 건드리지 않는다.

```bash
docker compose --env-file .env -f infra/local/compose.yml up -d postgres
MSYS_NO_PATHCONV=1 docker run --rm --network reposcope-local_default \
  --env-file .env -e PYTHONDONTWRITEBYTECODE=1 \
  -v "$PWD:/src:ro" -w /src python:3.13-slim sh -c '
    pip install -q --root-user-action=ignore -r backend/worker/requirements.txt pytest==9.2.0 &&
    WORKER_TEST_DB_DSN="postgresql://${DB_USER:-reposcope}:${DB_PASSWORD}@postgres:5432/${DB_NAME:-reposcope}" \
    python -m pytest -v -p no:cacheprovider backend/worker/tests'
```

PowerShell에서는 `MSYS_NO_PATHCONV=1`을 빼고 `-v "${PWD}:/src:ro"`로 쓴다. skip 없이 모두 통과해야 한다. `WORKER_TEST_DB_DSN`이 없으면 DB 테스트는 skip된다.

## 로컬 확인 (수신 함수 없이)

S3에 fixture를 올리고 SQS에 메시지를 넣어 수신 함수를 흉내 낸다. 저장소 루트에서 Git Bash로 실행한다.

```bash
export MSYS_NO_PATHCONV=1
C="docker compose --env-file .env -f infra/local/compose.yml --profile ingest"
Q=http://moto:5000/123456789012/reposcope-events
aws_() { $C run --rm --no-deps -v "$PWD/backend/worker/tests/fixtures/github:/f:ro" --entrypoint aws moto-init "$@"; }
psql_() { $C exec -T postgres sh -c "psql -qAt -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$1\""; }
enqueue() {  # 이벤트, delivery_id
  aws_ sqs send-message --queue-url $Q --message-body "{\"schema_version\":1,\"source\":\"webhook\",\"delivery_id\":\"$2\",\"event\":\"$1\",\"s3_key\":\"2026-11-11/$2.json\",\"received_at\":\"2026-11-11T00:00:00Z\"}"
}
send() {     # fixture, 이벤트, delivery_id
  aws_ s3 cp "/f/$1.json" "s3://reposcope-raw-events/2026-11-11/$3.json" && enqueue "$2" "$3"
}

send installation.created installation d-install          # 저장소 2개 LINKED
```

| 확인 | 보내는 것 | 기대 결과 |
|---|---|---|
| 중복 | `send push push d-push` → `enqueue push d-push`(같은 delivery) → `send push push d-push-2`(같은 커밋, 다른 delivery) | `psql_ "SELECT count(*) FROM activities WHERE kind='COMMIT'"` = `2`, `d-push`의 `processed_deliveries` 1행, 로그에 `"result": "DUPLICATE"` |
| 순서 | `send issues.closed issues d-ic` → `send issues.opened issues d-io` | `psql_ "SELECT state FROM activities WHERE kind='ISSUE'"` = `closed` (1행) |
| DLQ | `enqueue push d-missing` (S3 원본 없음) | 로그에 `message_failed` 5회, 약 5분 뒤 `aws_ sqs get-queue-attributes --queue-url http://moto:5000/123456789012/reposcope-events-dlq --attribute-names ApproximateNumberOfMessages` = `1` |

`INGEST_SCHEMA`를 `public`이 아닌 값으로 쓰면 `psql_`의 SQL 앞에 `SET search_path TO <스키마>;`를 붙인다.

## 자주 겪는 문제

| 증상 | 원인과 해결 |
|---|---|
| `startup_failed`, `DuplicateTable` | psql로 직접 마이그레이션을 적용한 DB라 적용 이력(`worker_schema_migrations`)이 없다. `down -v`로 DB를 비우거나, `.env`의 `INGEST_SCHEMA`를 다른 이름으로 정한다. |
| `startup_failed`, `RuntimeError` | 이미 적용한 마이그레이션 파일이 바뀌었거나 지워졌다. 파일을 되돌리고 바꿀 내용은 새 번호로 추가한다. |
| `startup_failed`, `ValueError` | 필수 환경 변수가 없거나 `INGEST_SCHEMA` 값이 잘못됐다. |
| `github_disabled` | App ID나 키 파일이 없다. 로컬에서는 정상이다. |

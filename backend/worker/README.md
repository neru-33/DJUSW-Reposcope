# 수집 worker

SQS 메시지를 읽어 S3 원본 payload를 가져오고, 수집 테이블에 멱등 upsert한다(기능 명세 S-2). 연결된 저장소의 과거 기록과 README는 GitHub API로 가져온다(S-3). Python 3.13, boto3, psycopg 3으로 구현했다.

```text
SQS <접두어>-events → worker → S3 <접두어>-raw-events (원본) → PostgreSQL (INGEST_SCHEMA)
                      └ 5회 실패 → <접두어>-events-dlq
GitHub API → worker 과거 기록 스레드 → PostgreSQL (큐를 거치지 않는다)
```

## 원본

`Onezero-GDGoC-DJU/2026URP` 커밋 `4c778e6b3a81d343cb09adbeb2f24ddae43bcf8f`에서 가져왔다. 메시지 형식은 그 저장소의 `docs/contracts/sqs-message.schema.json`, 테이블은 `docs/contracts/db-schema.sql`과 같다.

| RepoScope 경로 | 원본 경로 | 변경 |
|---|---|---|
| `queue.py`, `log.py`, `__init__.py`, `requirements.txt`, `Dockerfile` | `backend/worker/` 같은 이름 | 없음 |
| `message.py` | `backend/worker/message.py` | 메시지 `event` 값에 `pull_request_review` 추가. 원본 계약 `docs/contracts/sqs-message.schema.json`의 `event` 값에는 없다 |
| `events.py` | `backend/worker/events.py` | `pull_request_review`를 활동 처리로 넘김, 기본 브랜치 push 뒤 README 다시 가져오기, 설치에서 빠진 저장소의 README 삭제와 과거 기록 작업 `SKIPPED` 처리 |
| `activities.py` | `backend/worker/activities.py` | 리뷰 저장, `parent_ref`·`merged_at` 저장, 리뷰의 `dismissed`를 최종 상태로 처리, REST 리뷰 변환(`rest_review_row`) 추가 |
| `backfill.py` | `backend/worker/backfill.py` | 리뷰(가장 최근 PR 500개)와 README 가져오기·재시도 추가, 저장소 전체 선택 설치는 README만 가져옴, 작업 결과는 `PENDING`인 작업에만 기록 |
| `tests/test_queue.py` | `backend/worker/tests/test_queue.py` | 없음 |
| `tests/test_github.py` | `backend/worker/tests/test_github.py` | 조건부 요청 테스트 추가 |
| `tests/fixtures/github/*.json` | `fixtures/github/*.json` | 없음. `pull_request_review.*.json`은 RepoScope에서 추가 |
| `tests/contract/db-schema.sql` | `docs/contracts/db-schema.sql` | 없음 |
| `__main__.py` | `backend/worker/__main__.py` | 시작 시 `INGEST_SCHEMA` 스키마 생성 |
| `config.py` | `backend/worker/config.py` | `INGEST_SCHEMA` 추가 |
| `db.py` | `backend/worker/db.py` | 접속 `search_path`를 `INGEST_SCHEMA`로 지정, `ensure_schema` 추가(스키마가 없을 때만 생성) |
| `github.py` | `backend/worker/github.py` | `User-Agent`를 `reposcope-worker`로 변경, ETag 조건부 요청(`conditional_get`) 추가 |
| `tests/test_config.py` | `backend/worker/tests/test_config.py` | `INGEST_SCHEMA` 테스트 추가 |
| `tests/test_database.py` | `backend/worker/tests/test_database.py` | 계약 비교에 V9, V10 포함(컬럼·제약·인덱스), 스키마 분리 테스트 추가, 리뷰·병합 시각 테스트 추가, 과거 기록 테스트에 리뷰·README·전체 선택·재추가·읽는 중 제거 경우 추가, README 테스트(재시도, 늦게 도착한 제거 이벤트 포함) 추가 |

`readme.py`는 RepoScope에서 추가했다.

## 처리 규칙

- `processed_deliveries`로 같은 `delivery_id`를 한 번만 반영한다. 동시에 들어온 중복도 같은 트랜잭션에서 하나만 처리된다.
- 처리 결과를 DB에 커밋한 뒤에만 SQS 메시지를 삭제한다. 실패하면 삭제하지 않으므로 5회 수신 뒤 DLQ로 이동한다.
- 활동은 `(repo_id, kind, ref)`로 upsert한다. 늦게 도착한 `opened`는 `closed`·`merged`를 되돌리지 않고, PR의 `merged`와 리뷰의 `dismissed`는 최종 상태다. 이미 저장한 행의 작성자와 `occurred_at`은 바꾸지 않는다.
- PR의 `merged_at`은 병합 시각이 있는 이벤트로만 채우고, 값이 없는 이벤트로 지우지 않는다. `merged`인데 병합 시각이 없으면 `NULL`로 둔다.
- 리뷰는 리뷰 ID를 `ref`, 소속 PR 번호를 `parent_ref`, 제출 시각을 `occurred_at`으로 저장한다. 상태는 소문자로 저장하고 `pending`은 저장하지 않는다. PR 행보다 먼저 와도 저장한다. 제출 시각이 없는 리뷰(`pending` 제외)는 처리 실패로 보고 DLQ로 보낸다.
- `LINKED`가 아닌 저장소의 활동, 태그 push, 브랜치 삭제는 저장하지 않는다. 대상이 아닌 이벤트는 `IGNORED`로 기록하고 메시지를 삭제한다.
- 커밋은 메시지 첫 줄만 저장한다. 이메일, 커밋 본문, PR·이슈·리뷰 본문, diff는 저장하지 않는다. 로그에는 ID와 오류 종류만 남긴다.
- 처리하는 이벤트는 `installation`, `installation_repositories`, `push`, `pull_request`, `pull_request_review`, `issues`다. 그 밖의 이벤트는 메시지 파싱에서 실패해 DLQ로 간다.

## 과거 기록

`backfill.py`가 별도 스레드에서 실행한다. 큰 저장소를 읽는 동안에도 이벤트 처리는 계속된다. GitHub 연동이 꺼져 있거나 `BACKFILL=off`이면 실행하지 않는다.

- `LINKED`이고 설치가 `ACTIVE`인 저장소마다 `backfill_jobs`에 작업을 만들고 하나씩 처리한다.
- 커밋(기본 브랜치와 다른 브랜치 30개까지), PR, 리뷰, 이슈와 README를 가져와 이벤트 처리와 같은 `activities.upsert`로 저장한다. 큐를 거치지 않는다.
- 목록 하나당 3,000건까지 읽고, 리뷰는 가장 최근 PR 500개까지만 읽는다. 상한에 걸리면 `DONE`이되 `last_error`가 `truncated`다.
- `BACKFILL_DAYS`가 0보다 크면 그 기간의 활동만 가져온다. 포크한 저장소는 포크한 시점 이후 커밋만 가져온다.
- README는 활동을 저장하고 작업을 끝낸 뒤 가져온다. 가져오지 못하면 상태 코드와 저장소 ID만 로그(`readme_failed`)에 남기고 작업은 그대로 `DONE`이다. README는 아래 [README](#readme)의 재시도로 다시 가져온다.
- 저장소 전체 선택(`all`)으로 설치한 경우 활동 기록은 가져오지 않고 README만 가져온다. README를 가져왔는지와 관계없이 작업은 `SKIPPED`, `last_error`는 `all_repositories`이고, 설치가 `selected`로 바뀌면 다시 처리한다.
- 저장소가 설치에서 빠지면 작업을 `SKIPPED`(`not_linked`)로 바꾼다. 다시 추가되면 처음부터 다시 가져온다. upsert이므로 중복은 생기지 않는다.
- 작업 결과(`DONE`, `FAILED`, 재시도 시각)는 `PENDING`인 작업에만 기록한다. 읽는 사이 저장소가 빠져 `SKIPPED`가 된 작업은 이어진 GitHub 요청이 실패해도 `FAILED`나 재시도로 바뀌지 않으므로, 다시 추가하면 바로 처리된다.
- GitHub 요청이 실패하면 10분 × 시도 횟수 뒤에 다시 하고, 5번째 실패면 `FAILED`다. 저장소가 없으면(404, 410, 451) 바로 `FAILED`다. `FAILED`는 하루 뒤 다시 처리한다. 빈 저장소, 읽는 사이 지워진 브랜치, PR이나 이슈를 끈 저장소는 그 부분만 건너뛴다.

## README

`readme.py`가 `GET /repos/{저장소}/readme`로 가져와 `repo_readmes`에 저장한다.

- 저장소가 연결될 때(과거 기록 작업)와 기본 브랜치에 push가 올 때 가져온다. 브랜치 삭제 push는 제외한다.
- 과거 기록 스레드는 반복할 때마다 설치가 `ACTIVE`인 `LINKED` 저장소 중 `repo_readmes`에 행이 없고 과거 기록 작업이 `PENDING`이 아닌 저장소의 README를 다시 가져온다. README가 원래 없는 저장소(`404`)에 계속 요청하지 않도록 저장소마다 마지막 시도 시각을 worker 메모리에 두고 1시간이 지난 저장소만 요청한다. 한 번 반복에 20개까지 요청한다. 시도 시각은 DB에 두지 않으므로 worker를 다시 시작하면 처음부터 센다. `BACKFILL=off`이면 재시도도 하지 않는다.
- 저장한 ETag로 조건부 요청(`If-None-Match`)을 보낸다. `304`이면 본문은 그대로 두고 `fetched_at`만 바꾼다.
- 512KB 이하이고 NUL 문자가 없는 UTF-8 README만 저장한다. README가 없거나(`404`), 512KB를 넘거나, UTF-8이 아니면 저장한 README를 지운다.
- 저장소가 설치에서 빠지면(`installation_repositories.removed`, `installation.deleted`) 저장한 README를 지운다. 설치가 `suspend`되면 그대로 둔다.
- push 때는 커밋을 저장한 뒤 트랜잭션 밖에서 가져온다. 실패하면 상태 코드와 저장소 ID만 로그(`readme_failed`)에 남기고 메시지는 실패시키지 않는다. 다음 push 때 다시 가져온다.

## 마이그레이션

시작할 때 `INGEST_SCHEMA` 스키마가 없으면 만들고 `backend/migrations/V*__*.sql`을 번호 순서대로 적용한다. 적용 이력은 같은 스키마의 `worker_schema_migrations`에 파일 이름과 체크섬으로 남는다. 여러 worker가 동시에 시작해도 advisory lock으로 한 번만 적용된다. 이미 적용한 파일을 고치거나 지우면 시작하지 않는다.

worker의 접속 `search_path`에는 `INGEST_SCHEMA`만 있으므로 `capstone` 스키마를 읽거나 쓰지 않는다. `INGEST_SCHEMA`가 `CAPSTONE_SCHEMA`와 같으면 시작하지 않는다.

## 환경 변수

| 변수 | 필수 | 로컬 값 |
|---|:-:|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SSLMODE` | ✔ | compose가 `.env` 값으로 넣는다 |
| `INGEST_SCHEMA` | | 기본 `public`. 영문 소문자, 숫자, `_` |
| `CAPSTONE_SCHEMA` | | 기본 `capstone`. 같은 이름을 막는 데만 쓴다 |
| `QUEUE_URL` | ✔ | `http://moto:5000/123456789012/<접두어>-events` (`<접두어>`는 `.env`의 `LOCAL_NAME_PREFIX`) |
| `RAW_BUCKET` | ✔ | `<접두어>-raw-events` |
| `AWS_REGION` | ✔ | `ap-northeast-2` |
| `AWS_ENDPOINT_URL` | | 로컬만 `http://moto:5000`. AWS에서는 넣지 않는다 |
| `GITHUB_APP_ID` | | 비우면 GitHub 연동을 끈다 |
| `GITHUB_APP_PRIVATE_KEY_FILE` | | 기본 `/run/secrets/github-app-key`. 로컬에서는 compose가 `.env`의 같은 이름 변수(내 컴퓨터의 PEM 경로)를 이 위치에 읽기 전용으로 넣고, 비워 두면 빈 파일이 들어간다. 파일이 비었거나 PEM이 아니면 GitHub 연동을 끈다 |
| `BACKFILL` | | 기본 켜짐. `off`, `0`, `false`이면 과거 기록을 가져오지 않는다 |
| `BACKFILL_DAYS` | | 기본 `0`(전체 기록). 1~3650이면 그 기간(일)의 활동만 가져온다. 그 밖의 값은 `0`으로 본다 |
| `APP_VERSION` | | 시작 로그에 남긴다 |

GitHub 연동은 저장소 전체 선택(`all`)으로 설치했을 때 설치 저장소 목록을 조회하고, 과거 기록과 README를 가져오는 데 쓴다. 꺼져 있으면 payload에 들어 있는 저장소만 저장하고 과거 기록과 README는 가져오지 않는다.

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
    WORKER_TEST_DB_DSN="postgresql://${DB_USER}:${DB_PASSWORD}@postgres:5432/${DB_NAME}" \
    python -m pytest -v -p no:cacheprovider backend/worker/tests'
```

PowerShell에서는 `MSYS_NO_PATHCONV=1`을 빼고 `-v "${PWD}:/src:ro"`로 쓴다. skip 없이 모두 통과해야 한다. `WORKER_TEST_DB_DSN`이 없으면 DB 테스트는 skip된다.

CI(`.github/workflows/ci.yml`의 `worker` job)도 같은 이미지와 명령으로 실행하고, 건너뛴 테스트가 있으면 실패한다.

## 로컬 확인 (수신 함수 없이)

S3에 fixture를 올리고 SQS에 메시지를 넣어 수신 함수를 흉내 낸다. 저장소 루트에서 Git Bash로 실행한다.

```bash
export MSYS_NO_PATHCONV=1
C="docker compose --env-file .env -f infra/local/compose.yml --profile ingest"
P="$(grep '^LOCAL_NAME_PREFIX=' .env | cut -d= -f2-)"   # moto 자원 이름의 접두어
Q=http://moto:5000/123456789012/$P-events
aws_() { $C run --rm --no-deps -v "$PWD/backend/worker/tests/fixtures/github:/f:ro" --entrypoint aws moto-init "$@"; }
psql_() { $C exec -T postgres sh -c "psql -qAt -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$1\""; }
enqueue() {  # 이벤트, delivery_id
  aws_ sqs send-message --queue-url $Q --message-body "{\"schema_version\":1,\"source\":\"webhook\",\"delivery_id\":\"$2\",\"event\":\"$1\",\"s3_key\":\"2026-11-11/$2.json\",\"received_at\":\"2026-11-11T00:00:00Z\"}"
}
send() {     # fixture, 이벤트, delivery_id
  aws_ s3 cp "/f/$1.json" "s3://$P-raw-events/2026-11-11/$3.json" && enqueue "$2" "$3"
}

send installation.created installation d-install          # 저장소 2개 LINKED
```

| 확인 | 보내는 것 | 기대 결과 |
|---|---|---|
| 중복 | `send push push d-push` → `enqueue push d-push`(같은 delivery) → `send push push d-push-2`(같은 커밋, 다른 delivery) | `psql_ "SELECT count(*) FROM activities WHERE kind='COMMIT'"` = `2`, `d-push`의 `processed_deliveries` 1행, 로그에 `"result": "DUPLICATE"` |
| 순서 | `send issues.closed issues d-ic` → `send issues.opened issues d-io` | `psql_ "SELECT state FROM activities WHERE kind='ISSUE'"` = `closed` (1행) |
| DLQ | `enqueue push d-missing` (S3 원본 없음) | 로그에 `message_failed` 5회, 약 5분 뒤 `aws_ sqs get-queue-attributes --queue-url http://moto:5000/123456789012/$P-events-dlq --attribute-names ApproximateNumberOfMessages` = `1` |

`INGEST_SCHEMA`를 `public`이 아닌 값으로 쓰면 `psql_`의 SQL 앞에 `SET search_path TO <스키마>;`를 붙인다.

## 자주 겪는 문제

| 증상 | 원인과 해결 |
|---|---|
| `startup_failed`, `DuplicateTable` | psql로 직접 마이그레이션을 적용한 DB라 적용 이력(`worker_schema_migrations`)이 없다. `down -v`로 DB를 비우거나, `.env`의 `INGEST_SCHEMA`를 다른 이름으로 정한다. |
| `startup_failed`, `RuntimeError` | 이미 적용한 마이그레이션 파일이 바뀌었거나 지워졌다. 파일을 되돌리고 바꿀 내용은 새 번호로 추가한다. |
| `startup_failed`, `ValueError` | 필수 환경 변수가 없거나 `INGEST_SCHEMA` 값이 잘못됐다. |
| `github_disabled` | App ID나 키 파일이 없다. 로컬에서는 정상이다. 과거 기록과 README도 가져오지 않는다. |

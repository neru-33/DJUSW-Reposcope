# 로컬 실행 환경

내 컴퓨터에서 서비스를 실행하는 방법이다. AWS 계정과 자격 증명이 필요 없다.

| 구성 | 서비스 | 사용자 |
|---|---|---|
| 기본 | postgres, api | 모든 팀원 |
| 수집 | 기본 구성 + moto, moto-init, ingest, worker | 수집, 인프라 담당 |

moto는 S3, SQS, SSM Parameter Store를 흉내 내는 오픈소스 AWS 에뮬레이터다. ingest는 API Gateway와 수신 Lambda를 대신해 운영과 같은 핸들러를 실행한다([수신 Lambda 안내](../../webhook/README.md)). worker 안내는 [여기](../../backend/worker/README.md)에 있다. 화면은 해당 작업에서 이 compose 파일에 서비스로 추가한다.

## 필요한 것

- Docker Desktop (`docker compose` 명령이 동작해야 한다)
- Git

## 처음 실행

저장소 루트에서 실행한다.

1. `.env.example`을 `.env`로 복사한다.
2. `.env`에서 `DB_PASSWORD`에 로컬에서만 쓰는 임의 값을 입력한다. `DB_USER`를 비워 두면 `reposcope`를 쓴다. 수집 구성을 쓰면 `LOCAL_WEBHOOK_SECRET`에 32자 이상의 임의 값을 입력한다. 수신 함수는 32자보다 짧은 비밀값을 설정되지 않은 것으로 보고 `503`으로 응답한다. `.env`는 커밋하지 않는다.
3. Docker Desktop을 실행해 둔다.

기본 구성:

```bash
docker compose --env-file .env -f infra/local/compose.yml up -d --build
```

수집 구성:

```bash
docker compose --env-file .env -f infra/local/compose.yml --profile ingest up -d --build
```

## 확인

| 확인 | 명령 | 기대 결과 |
|---|---|---|
| API 상태 | `curl -i http://localhost:8080/api/health` | `200` |
| API 버전 | `curl -i http://localhost:8080/api/version` | `APP_VERSION` 값 |
| DB 준비 | `docker compose --env-file .env -f infra/local/compose.yml ps` | postgres가 `healthy` |
| moto 초기화(수집) | `docker compose --env-file .env -f infra/local/compose.yml --profile ingest logs moto-init` | `로컬 AWS 준비 완료` |
| 큐 목록(수집) | `docker compose --env-file .env -f infra/local/compose.yml --profile ingest run --rm --no-deps --entrypoint aws moto-init sqs list-queues` | `reposcope-events`, `reposcope-events-dlq` |
| 수신 함수 상태(수집) | `curl -i http://localhost:9000/health` | `200` |
| worker 시작(수집) | `docker compose --env-file .env -f infra/local/compose.yml --profile ingest logs worker` | `worker_started` |

## 접속 주소

| 대상 | 주소 | 비고 |
|---|---|---|
| API | `http://localhost:8080` | localhost에서만 접근 |
| PostgreSQL | `localhost:5432` | localhost에서만 접근 |
| 수신 함수 | `http://localhost:9000/webhook` | 수집 구성, localhost에서만 접근 |
| moto | `http://localhost:4566` | 수집 구성, localhost에서만 접근 |

## moto가 만드는 자원

`moto-init`이 시작할 때 아래 자원을 만든다. moto는 재시작하면 내용이 사라지므로 `up`을 다시 실행하면 다시 만든다.

| 종류 | 이름 | 설정 |
|---|---|---|
| S3 | `reposcope-raw-events` | 원본 이벤트 보관 |
| SQS | `reposcope-events` | 가시성 타임아웃 60초, 롱 폴링 20초, 5회 수신 실패 시 DLQ |
| SQS | `reposcope-events-dlq` | 실패 메시지 보관 |
| SSM | `/reposcope/webhook-secret`, `/reposcope/db-password`, `/reposcope/github-app-key` | `github-app-key`는 `CHANGE_ME` |

AWS SDK를 쓰는 코드는 `AWS_ENDPOINT_URL` 환경 변수가 있으면 moto로 접속하므로 코드에 주소를 쓰지 않는다. compose 안의 컨테이너는 `http://moto:5000`, 내 컴퓨터에서 실행하는 프로그램은 `http://localhost:4566`을 쓴다.

## 종료와 초기화

| 하는 일 | 명령 |
|---|---|
| 종료 (DB 데이터 유지) | `docker compose --env-file .env -f infra/local/compose.yml --profile ingest down` |
| 종료 후 DB 데이터 삭제 | 위 명령에 `-v`를 붙인다 |
| 코드를 바꾼 뒤 API·수신 함수·worker 다시 빌드 | `up -d --build`를 다시 실행한다 |

## 운영과 다른 점

| 항목 | 로컬 | AWS |
|---|---|---|
| 자격 증명 | 고정 값 `test` (moto는 검사하지 않음) | 실행 역할 |
| 권한 | 검사 없음 | 역할별 최소 권한 |
| DB 접속 | SSL 없음 (`DB_SSLMODE=disable`) | RDS는 SSL을 사용 |
| SQS 전달 | 대체로 한 번씩, 순서대로 | 표준 큐는 같은 메시지가 중복되거나 순서가 바뀔 수 있음 |
| 저장 | moto는 메모리에만 저장 | 영구 저장 |
| 네트워크 | 모든 포트가 localhost에만 열림 | 보안 그룹으로 제한 |

로컬 시험을 통과해도 IAM 권한 누락이나 SSL 설정 문제는 AWS에서만 드러난다.

## 자주 겪는 문제

| 증상 | 원인과 해결 |
|---|---|
| `failed to connect to the docker API` | Docker Desktop이 실행 중이 아니다. 실행한 뒤 다시 시도한다. |
| `.env에 DB_PASSWORD를 설정한다` | `.env`를 만들지 않았거나 값이 비어 있다. 처음 실행 2번을 한다. |
| `port is already allocated` (5432, 8080, 4566, 9000) | 같은 포트를 쓰는 프로그램이 있다. 종료하거나 `.env`의 `DB_PORT`를 바꾼다(5432만 변경 가능). |
| API가 DB에 접속하지 못함 | postgres가 `healthy`가 될 때까지 기다린 뒤 `logs api`를 확인한다. |
| DB 비밀번호를 바꿨는데 반영되지 않음 | 기존 볼륨에 이전 비밀번호가 남아 있다. `down -v`로 지우고 다시 실행한다. |

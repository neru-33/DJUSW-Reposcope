# AGENTS.md

AI 코딩 도구가 이 저장소에서 작업할 때 지킬 규칙이다. 사람용 안내는 [작업 가이드](docs/work-guide.md)와 [기여 규칙](CONTRIBUTING.md)에 있다.

## 프로젝트

RepoScope는 캡스톤디자인 프로젝트의 기본정보, 팀 구성, 진행 상태, 결과물을 관리하고 연결된 GitHub 저장소의 활동을 수집해 보여 주는 웹 서비스다.

```text
GitHub App Webhook → API Gateway → Lambda → S3 원본 + SQS(+DLQ) → worker → PostgreSQL
사용자 → React 화면 → Spring API → PostgreSQL
```

## 기준 문서

작업 전에 관련 절을 읽는다. 문서와 다르게 구현해야 하면 코드를 쓰기 전에 사용자에게 묻는다.

| 문서 | 내용 |
|---|---|
| `docs/work-plan.md` | 작업 번호, 영역, 선행 관계, 폴더 |
| `docs/feature-spec.md` | 기능 규칙과 완료 조건 |
| `docs/screen-flow.md` | 화면 목록과 이동 경로 |
| `docs/ui-guide.md` | UI 라이브러리, 색, 글꼴, 화면 유형별 배치 |
| `docs/data-model.md` | 테이블, 제약 조건, 테이블 소유 |
| `docs/api-spec.md` | API 경로, 요청, 응답, 오류 코드 |
| `docs/work-guide.md` | 작업별 참고 위치 |

## 폴더

| 폴더 | 내용 |
|---|---|
| `frontend/` | React 화면 |
| `backend/api/` | Spring API |
| `backend/worker/` | 수집 worker |
| `backend/migrations/` | 수집·계정 테이블 마이그레이션 |
| `webhook/` | Webhook 수신 Lambda |
| `infra/` | Terraform, 배포 설정, 로컬 실행 환경 |

## 규칙

- `main`에 직접 push하지 않는다. 이슈 번호를 넣은 브랜치에서 작업하고 PR로 합친다.
- 커밋 메시지와 PR 제목은 Conventional Commits 형식에 폴더 범위를 붙인다. 예: `feat(backend): 프로젝트 API 추가`
- 맡은 이슈의 범위만 바꾼다. 다른 문제를 발견하면 따로 알린다.
- 비밀값, 키 파일, 개인정보를 코드, 테스트, 로그, 커밋에 넣지 않는다. 설정은 환경 변수로만 받는다.
- 코드, diff, 커밋 작성자 이메일, 커밋 본문, PR·이슈 본문, GitHub 토큰은 DB에 저장하지 않는다.
- API는 worker 소유 테이블에 쓰지 않는다. worker는 `capstone` 스키마를 읽거나 쓰지 않는다.
- `capstone` 테이블은 수집·계정 테이블에 외래 키를 걸지 않는다.
- 현재 사용자 확인은 한 지점에서만 한다. 권한 검사는 화면뿐 아니라 API에서도 한다.
- AWS 자원은 `infra/`의 Terraform으로만 바꾼다. 콘솔이나 CLI로 자원을 만드는 명령을 실행하지 않는다.
- 문서와 주석에는 최종 내용만 쓴다. 요청 경위, 결정 과정, 대화 내용은 PR 본문이나 답변에 쓴다.
- 문서는 한국어로 쓴다.

## 연계 프로젝트

수집 기능은 `Onezero-GDGoC-DJU/2026URP`의 구현을 가능한 범위에서 재사용한다.

- 메시지 형식은 그 저장소의 `docs/contracts/sqs-message.schema.json`, 수집 테이블은 `docs/contracts/db-schema.sql`과 같아야 한다.
- 형식이나 저장 규칙을 바꿔야 하면 구현하기 전에 사용자에게 알린다.
- 가져온 코드의 원본 경로와 커밋을 PR 본문에 적는다.
- 자원 이름과 Parameter Store 경로는 RepoScope 값으로 바꾼다.

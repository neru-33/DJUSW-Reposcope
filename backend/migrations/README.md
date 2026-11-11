# 수집·계정 마이그레이션

수집 영역과 계정 영역 테이블을 만드는 SQL이다. worker가 시작할 때 적용한다. capstone 스키마는 API가 Flyway로 따로 적용한다([데이터 모델](../../docs/data-model.md) 9장).

## 원본

V1~V8은 `Onezero-GDGoC-DJU/2026URP`의 `backend/migrations/`를 파일 이름과 내용 그대로 가져왔다.

| 항목 | 값 |
|---|---|
| 원본 경로 | `backend/migrations/V1__m1_init.sql` ~ `V8__operator_expiry.sql` |
| 원본 커밋 | `4c778e6b3a81d343cb09adbeb2f24ddae43bcf8f` |
| 계약 파일 | `docs/contracts/db-schema.sql` (V1~V8 적용 결과와 같다) |

V9부터는 RepoScope에서 추가한 파일이다.

| 파일 | 내용 |
|---|---|
| `V1__m1_init.sql` | `installations`, `repos`, `processed_deliveries` |
| `V2__p0_tables.sql` | `users`, `teams`, `team_members`, `projects`, `activities`, `audit_logs` |
| `V3__backfill_jobs.sql` | `backfill_jobs` |
| `V4__programs.sql` | `programs`, `repos.program_id` |
| `V5__user_repos.sql` | `user_repos` |
| `V6__user_profile.sql` | `users`의 이름, 학번, 학과, 가입 시각 |
| `V7__operators.sql` | 직위 5단계, `operator_credentials`, `operator_sessions`, `login_attempts` |
| `V8__operator_expiry.sql` | `operator_credentials.expires_at` |
| `V9__reposcope_additions.sql` | `activities`의 `REVIEW`, `parent_ref`, `merged_at`, `(repo_id, actor_github_id)` 인덱스, `users.disabled_at`, 학번 형식 CHECK |

## 사용하지 않는 테이블과 컬럼

`teams`, `team_members`, `projects`, `programs`, `audit_logs` 테이블과 `installations.team_id`, `repos.project_id`, `repos.program_id` 컬럼은 2026URP 계약 유지용이며 RepoScope에서는 사용하지 않는다. 프로젝트, 팀원, 감사 기록은 capstone 스키마의 테이블을 쓴다.

## 규칙

- 파일 이름은 `V<번호>__<설명>.sql`이다. 번호 순서대로 적용한다.
- 이미 적용한 파일은 고치거나 지우지 않는다. 바꿀 내용은 다음 번호의 새 파일로 추가한다.
- SQL에 스키마 이름을 쓰지 않는다. 적용하는 쪽이 `search_path`를 `INGEST_SCHEMA`(기본 `public`) 값으로 정한다.
- 한 PR에서 capstone 마이그레이션과 함께 바꾸면 이 폴더를 먼저 적용한다.

## 로컬 적용

worker가 없을 때 psql로 직접 적용하는 방법이다. 저장소 루트에서 실행하고, postgres가 떠 있어야 한다([로컬 실행 환경](../../infra/local/README.md)).

```bash
C="docker compose --env-file .env -f infra/local/compose.yml"
SCHEMA="${INGEST_SCHEMA:-public}"

$C exec -T postgres psql -U reposcope -d reposcope -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\""
for f in $(ls backend/migrations/V*__*.sql | sort -V); do
  $C exec -T -e PGOPTIONS="-c search_path=$SCHEMA" postgres \
    psql -v ON_ERROR_STOP=1 -U reposcope -d reposcope < "$f" || break
done
```

이 방법은 적용 이력을 남기지 않으므로 빈 DB에 한 번만 실행한다. 다시 적용하려면 `down -v`로 DB를 비운다.

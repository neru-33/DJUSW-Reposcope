# API 서버

Java 21과 PostgreSQL을 사용한다. 환경 변수를 설정한 뒤 `./gradlew bootRun`으로 실행한다.

`/api/version`은 `APP_VERSION` 환경 변수 값을 반환한다. 배포 시 이미지 태그와 같은 값으로 설정하며, 지정하지 않으면 `unknown`을 반환한다.

PostgreSQL 연결을 위해 다음 환경 변수를 실행 환경에 설정한다.

| 변수 | 설명 | 예시 |
|---|---|---|
| `DB_HOST` | PostgreSQL 호스트 | `localhost` |
| `DB_PORT` | PostgreSQL 포트 | `5432` |
| `DB_NAME` | 데이터베이스 이름 | `reposcope` |
| `DB_USER` | DB 사용자 이름 | 환경에 맞게 설정 |
| `DB_PASSWORD` | DB 비밀번호 | 환경에 맞게 설정 |
| `DB_SSLMODE` | PostgreSQL SSL 모드 | `disable` |

루트의 `.env.example`은 변수 목록의 예시이며 Spring Boot가 `.env` 파일을 자동으로 읽지는 않는다. IntelliJ 실행 설정 또는 셸에서 환경 변수를 지정한다. 실제 비밀값은 저장소에 올리지 않는다.

## Docker 실행

루트의 `.env.example`을 `.env`로 복사하고 DB 접속 정보를 설정한 뒤 `backend/api/`에서 실행한다. Docker 컨테이너에서 `localhost`는 API 컨테이너 자신을 가리키므로, Docker Desktop의 호스트에서 실행 중인 PostgreSQL에 연결하려면 `DB_HOST=host.docker.internal`로 설정한다.

```bash
docker build -t reposcope-api .
docker run --rm -p 8081:8080 --env-file ../../.env --name reposcope-api-test reposcope-api
```

다른 터미널에서 `curl -i http://localhost:8081/api/health`와 `curl -i http://localhost:8081/api/version`으로 확인한다. 종료하려면 실행 중인 터미널에서 Ctrl+C를 누른다.

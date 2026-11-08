# API 서버

Java 21을 사용한다. DB 연결 없이 실행할 때는 `./gradlew bootRun`을 사용한다.

PostgreSQL을 사용할 때는 `SPRING_PROFILES_ACTIVE=db`와 다음 환경 변수를 실행 환경에 설정한다.

| 변수 | 설명 | 예시 |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC 주소 | `jdbc:postgresql://localhost:5432/reposcope` |
| `DB_USERNAME` | DB 사용자 이름 | 환경에 맞게 설정 |
| `DB_PASSWORD` | DB 비밀번호 | 환경에 맞게 설정 |

루트의 `.env.example`은 변수 목록의 예시이며 Spring Boot가 `.env` 파일을 자동으로 읽지는 않는다. IntelliJ 실행 설정 또는 셸에서 환경 변수를 지정한다. 실제 비밀값은 저장소에 올리지 않는다.

## Docker 실행

`backend/api/`에서 실행한다.

```bash
docker build -t reposcope-api .
docker run --rm -p 8081:8080 --name reposcope-api-test reposcope-api
```

다른 터미널에서 `curl -i http://localhost:8081/api/health`와 `curl -i http://localhost:8081/api/version`으로 확인한다. 종료하려면 실행 중인 터미널에서 Ctrl+C를 누른다.

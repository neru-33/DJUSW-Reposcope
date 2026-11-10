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

## 공통 인증과 권한

Spring Security는 `/api/health`, `/api/version`, GitHub 로그인 시작·콜백, 운영자 로그인 요청을 공개하고 나머지 요청에는 인증을 요구한다. 현재 로그인 API는 구현되어 있지 않다. 학생 로그인(T-20)과 운영자 로그인(T-24)은 아래 계약에 맞춰 인증을 연결한다.

### 로그인과 세션 연결

- 인증에 성공한 사용자는 `CurrentUser`의 사용자 ID와 서비스 역할로 표현한다. `CurrentUserService`는 Spring Security의 `SecurityContext`에서 `CurrentUser` 타입의 principal을 읽는다. 다른 타입을 저장하면 현재 사용자로 인정하지 않는다.
- 로그인 구현은 검증이 끝난 인증 정보로 `Authentication`을 만들고, 현재 요청의 `SecurityContext`를 세션에 저장해 다음 요청에서도 읽을 수 있게 해야 한다. `SecurityContextHolder`에만 설정하는 것은 세션 저장을 보장하지 않는다. 세션 고정 보호와 로그아웃 시 세션 종료도 로그인 작업에서 구현·검증한다.
- 운영자의 `PASSWORD_CHANGE`, `OTP_SETUP`, `OTP` 단계는 완전한 로그인과 구분해야 한다. 현재 `CurrentUser`에는 설정 단계 정보가 없으므로, 운영자 로그인 작업에서 단계별 인증 상태와 허용 경로를 추가해야 한다. OTP 확인 전에는 일반 API나 운영자 권한을 부여하지 않는다.
- 가입 완료 또는 사용자 역할 변경 시 세션의 `CurrentUser`를 갱신하고, 계정 비활성화나 권한 변경 시 기존 세션에 미치는 영향을 처리한다. 기존 principal은 사용자 ID와 역할의 스냅샷이다.

### API에서 현재 사용자와 권한 확인

컨트롤러나 서비스에 `CurrentUserService`를 주입해 다음 메서드를 사용한다.

| 메서드 | 용도 | 실패 응답 |
|---|---|---|
| `requireCurrentUser()` | 가입 전 사용자를 포함해 로그인만 필요할 때 | `401 unauthorized` |
| `requireCompletedProfile()` | 가입을 마친 사용자만 허용할 때 | 미로그인 `401`, `GUEST`는 `403 profile_required` |
| `requireRole(Role... roles)` | 지정된 서비스 역할만 허용할 때 | 미로그인 `401`, `GUEST`는 `403 profile_required`, 역할 불일치 `403 forbidden` |

예를 들어 사용자 관리 API에서는 `users.requireRole(CurrentUser.Role.SUPER_ADMIN, CurrentUser.Role.ADMIN)`을 호출한다. 역할은 호출부에 필요한 값만 지정한다. 이 메서드들은 호출한 곳에서만 검사하므로 각 API의 권한 규칙을 빠짐없이 적용해야 한다.

서비스 역할과 프로젝트 안의 팀장·팀원 역할은 다르다. 공통 서비스는 프로젝트 멤버십이나 대상 소유권을 검사하지 않는다. 프로젝트 API 구현 시 DB에서 대상별 권한을 따로 확인하고 권한이 없으면 `403 forbidden`을 반환한다.

### CSRF 토큰 연결

`CsrfTokenService.tokenFor(request)`는 현재 요청에서 클라이언트에 전달할 토큰을 가져온다. 후속 `GET /api/me`와 운영자 로그인 응답 구현은 이 값을 명세의 `csrf_token` 필드에 넣는다. 클라이언트는 같은 세션에서 `POST`, `PATCH`, `DELETE` 요청의 `X-CSRF-Token` 헤더로 토큰을 보낸다. `POST /auth/admin/login`은 검사 예외다.

로그인 과정에서 세션이나 CSRF 토큰을 교체한다면 교체 후 유효한 토큰을 응답해야 한다. 후속 로그인 작업에서 실제 로그인·설정 단계·로그아웃을 거친 토큰 발급과 검증을 통합 테스트한다. 공통 보안 테스트의 테스트용 로그인 경로는 실제 로그인 구현이 아니다.

## Docker 실행

루트의 `.env.example`을 `.env`로 복사하고 DB 접속 정보를 설정한 뒤 `backend/api/`에서 실행한다. Docker 컨테이너에서 `localhost`는 API 컨테이너 자신을 가리키므로, Docker Desktop의 호스트에서 실행 중인 PostgreSQL에 연결하려면 `DB_HOST=host.docker.internal`로 설정한다.

```bash
docker build -t reposcope-api .
docker run --rm -p 8081:8080 --env-file ../../.env --name reposcope-api-test reposcope-api
```

다른 터미널에서 `curl -i http://localhost:8081/api/health`와 `curl -i http://localhost:8081/api/version`으로 확인한다. 종료하려면 실행 중인 터미널에서 Ctrl+C를 누른다.

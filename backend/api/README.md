# API 서버

Java 21과 PostgreSQL을 사용한다. 환경 변수를 설정한 뒤 `./gradlew bootRun`으로 실행한다.

`/api/version`은 `APP_VERSION` 환경 변수 값을 반환한다. 배포 시 이미지 태그와 같은 값으로 설정하며, 지정하지 않으면 `unknown`을 반환한다.

세션 쿠키의 `Secure` 속성은 `SESSION_COOKIE_SECURE` 환경 변수로 설정한다. 기본값은 `false`이며 HTTPS 배포 환경에서는 `true`로 설정한다.

학생 세션은 로그인 시점부터 최대 8시간 유지되고, 로그아웃하면 무효화된다. `GET /api/me`의 `csrf_token`을 `POST /auth/logout`을 포함한 쓰기 요청의 `X-CSRF-Token` 헤더로 보낸다.

`CORS_ALLOWED_ORIGIN`은 자격 증명을 허용할 화면 출처 하나이며 기본값은 `http://localhost:5173`이다. 운영 환경에서는 `https://www.eclipse-sync.com`으로 설정한다. `FRONTEND_URL`은 로그인 후 이동할 화면 주소로, 기본값은 `http://localhost:5173`이며 운영 환경에서는 `https://www.eclipse-sync.com`으로 설정한다.

운영자 OTP 등록에는 `OPERATOR_OTP_KEY`가 필요하다. 세션과 독립적으로 안전하게 생성한 32바이트 키를 표준 Base64로 인코딩해 환경 변수로 전달한다. OTP 비밀키는 이 키로 AES-GCM 암호화해 저장한다. 서버 재시작과 인스턴스 간에 동일한 키를 유지해야 하며, 키를 바꾸거나 잃으면 기존 OTP 비밀키를 복호화할 수 없다. 키를 코드, 로그, 저장소에 넣지 않는다.

첫 `SUPER_ADMIN`을 만들 때만 `OPERATOR_BOOTSTRAP_USERNAME`과 `OPERATOR_BOOTSTRAP_PASSWORD`를 API 실행 환경에 함께 설정한다. 계정 마이그레이션과 API 마이그레이션이 적용된 뒤 API를 시작하면 한 번만 생성된다. 초기 비밀번호는 72시간 동안만 유효하며, 첫 로그인에서 ID와 비밀번호를 바꾸고 OTP를 등록해야 한다. 생성 후 두 초기 설정값을 실행 환경에서 제거하고 API를 재시작한다. 값은 코드나 저장소에 넣지 않는다. 두 값 중 하나만 지정하면 API가 시작되지 않는다.

GitHub 사용자 인증에는 `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GITHUB_CALLBACK_URL` 환경 변수를 사용한다. 콜백 URL은 GitHub App에 등록된 주소와 같아야 하며 로컬 기본값은 `http://localhost:8080/auth/github/callback`이다. 사용자 토큰은 인증 중 사용자 정보와 GitHub App 설치 저장소 조회에만 사용한다. 설치 및 저장소 목록을 모두 조회한 후 접근 가능 목록을 갱신한다. 조회가 실패하면 로그인도 실패하고 기존 목록은 유지된다. 계정 테이블의 스키마는 `INGEST_SCHEMA`로 지정하며 기본값은 `public`이다. 로컬 compose의 API에 값을 전달하려면 `.env`에 설정한다. Client secret은 저장소에 올리지 않는다.

사용자 IP는 신뢰할 수 있는 프록시가 전달한 `CF-Connecting-IP`에서 읽는다. 운영 API는 Cloudflare Tunnel을 거치지 않은 외부 요청을 받지 않아야 한다.

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

Spring Security는 `/api/health`, `/api/version`, GitHub 로그인 시작·콜백과 운영자 인증 경로를 공개한다. 운영자 인증 경로는 각 요청에서 설정 단계와 세션을 확인한다. 나머지 요청에는 인증이 필요하다.

### 로그인과 세션 연결

- 인증에 성공한 사용자는 `CurrentUser`의 사용자 ID와 서비스 역할로 표현한다. `CurrentUserService`는 Spring Security의 `SecurityContext`에서 `CurrentUser` 타입의 principal을 읽는다. 다른 타입을 저장하면 현재 사용자로 인정하지 않는다.
- 로그인 구현은 검증이 끝난 인증 정보로 `Authentication`을 만들고, 현재 요청의 `SecurityContext`를 세션에 저장해 다음 요청에서도 읽을 수 있게 해야 한다. `SecurityContextHolder`에만 설정하는 것은 세션 저장을 보장하지 않는다. 세션 고정 보호와 로그아웃 시 세션 종료도 로그인 작업에서 구현·검증한다.
- 운영자의 `PASSWORD_CHANGE`, `OTP_SETUP`, `OTP` 단계에서는 단계에 필요한 인증 경로만 사용한다. OTP 확인 후 `ACTIVE`가 되면 `CurrentUser`를 세션에 저장한다. 운영자 세션은 미사용 30분 또는 최초 로그인 8시간 뒤 만료되며, 역할이 변경되면 기존 세션을 무효화한다.
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

## 테스트

`./gradlew test`로 실행한다. DB가 필요한 테스트는 실행할 때 PostgreSQL 컨테이너(`postgres:17-alpine`)를 띄우므로 Docker가 실행 중이어야 한다. Docker가 꺼져 있으면 이 테스트는 실패한다. DB 접속 환경 변수는 필요 없다.

- 컨테이너에는 `backend/migrations/`의 수집·계정 마이그레이션을 `public` 스키마에 적용한 뒤 capstone 마이그레이션을 적용한다.
- DB가 필요한 테스트 클래스에는 `@DatabaseTest`를 붙인다. 이 애너테이션을 쓰는 테스트는 컨테이너 하나를 함께 쓴다.
- DB가 필요 없는 테스트는 컨테이너 없이 실행된다.

## Docker 실행

루트의 `.env.example`을 `.env`로 복사하고 DB 접속 정보를 설정한 뒤 `backend/api/`에서 실행한다. Docker 컨테이너에서 `localhost`는 API 컨테이너 자신을 가리키므로, Docker Desktop의 호스트에서 실행 중인 PostgreSQL에 연결하려면 `DB_HOST=host.docker.internal`로 설정한다.

```bash
docker build -t reposcope-api .
docker run --rm -p 8081:8080 --env-file ../../.env --name reposcope-api-test reposcope-api
```

다른 터미널에서 `curl -i http://localhost:8081/api/health`와 `curl -i http://localhost:8081/api/version`으로 확인한다. 종료하려면 실행 중인 터미널에서 Ctrl+C를 누른다.

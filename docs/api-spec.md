# API 명세

이 문서는 RepoScope 웹 화면과 Spring API 사이의 HTTP 계약을 정한다. 기능 번호는 [기능 명세](feature-spec.md), 화면 번호는 [화면 흐름](screen-flow.md), 테이블은 [데이터 모델](data-model.md)을 따른다. 여유 시 기능 R-11~R-13의 API는 구현할 때 추가한다.

## 1. 공통 규칙

- 경로는 `/api`와 `/auth`로 시작한다. 배포 환경의 기본 경로는 프록시에서 처리한다.
- 요청과 응답은 JSON이고, 필드 이름은 `snake_case`다.
- 시각은 UTC ISO 8601로 주고받는다. 예: `2026-11-07T01:23:45Z`
- 날짜는 `YYYY-MM-DD`로 주고받는다.
- 인증은 세션 쿠키를 쓴다. 쿠키는 `HttpOnly`다.
- `POST`, `PATCH`, `DELETE` 요청은 `X-CSRF-Token` 헤더를 보낸다. 값은 `GET /api/me`의 `csrf_token`이다. 운영자 로그인 요청은 제외한다.

### 목록 응답

목록 API는 `page`와 `size`를 받는다. `page`는 1부터 시작하고, `size`의 기본값과 최댓값은 50이다.

```json
{
  "items": [],
  "page": 1,
  "size": 50,
  "total": 0
}
```

### 오류 응답

```json
{
  "error": "invalid_request",
  "message": "프로젝트 이름은 1~100자로 입력하세요."
}
```

입력값 오류에는 필드별 내용을 담을 수 있다.

```json
{
  "error": "invalid_request",
  "message": "입력값을 확인하세요.",
  "fields": { "name": "1~100자로 입력하세요." }
}
```

| 상태 | `error` | 뜻 |
|---|---|---|
| 400 | `invalid_request` | 입력값이 잘못됨 |
| 400 | `consent_required` | 개인정보 동의가 없음 |
| 401 | `unauthorized` | 로그인 필요 |
| 401 | `invalid_credentials` | ID, 비밀번호, OTP가 틀림 |
| 401 | `temp_password_expired` | 임시 비밀번호가 만료됨 |
| 403 | `forbidden` | 권한 없음 |
| 403 | `profile_required` | 가입을 끝내지 않음 |
| 403 | `csrf` | CSRF 토큰이 없거나 틀림 |
| 404 | `not_found` | 대상 없음 |
| 409 | `duplicate_student_id` | 이미 쓰는 학번 |
| 409 | `project_archived` | 보관한 프로젝트라 수정할 수 없음 |
| 409 | `already_member` | 이미 참여 중 |
| 409 | `invalid_invite` | 초대 코드가 없거나 만료됨 |
| 409 | `last_leader` | 마지막 팀장을 바꾸거나 제외할 수 없음 |
| 409 | `repo_already_linked` | 저장소가 다른 프로젝트에 연결됨 |
| 423 | `locked` | 운영자 계정이 잠시 잠김 |
| 429 | `too_many_attempts` | 로그인 시도 횟수 초과 |
| 500 | `internal_error` | 서버 오류 |

다른 팀의 프로젝트를 요청하면 `403 forbidden`으로 응답한다.

## 2. 상태 확인

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| GET | `/api/health` | 없음 | `200 {"status":"ok"}` |
| GET | `/api/version` | 없음 | `200 {"version":"v0.1.0"}` (`APP_VERSION` 값, 미설정 시 `unknown`) |

## 3. 학생 인증 (R-1)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/auth/github/login?next=/projects` | 없음 | GitHub 로그인 시작. `next`는 같은 사이트 상대 경로만 허용 |
| GET | `/auth/github/callback` | 없음 | 로그인 완료 후 `next`로 이동 |
| POST | `/auth/logout` | 로그인 | 세션 종료, `204` |
| GET | `/api/me` | 로그인 | 현재 사용자 |
| POST | `/api/me/profile` | 로그인 | 가입 정보 저장, `204` |

### `GET /api/me`

```json
{
  "user": {
    "id": 7,
    "github_id": 1234567,
    "login": "student-a",
    "role": "STUDENT",
    "name": "홍길동"
  },
  "auth": "github",
  "csrf_token": "토큰",
  "consent_required": false,
  "profile_required": false
}
```

`github_id`와 `name`은 `null`일 수 있다. `auth`는 `github` 또는 `operator`다.

### `POST /api/me/profile`

```json
{
  "name": "홍길동",
  "student_id": "20261234",
  "department": "컴퓨터공학과",
  "consent": true
}
```

`department`는 선택이다. 실패하면 `400 invalid_request`, `400 consent_required`, `409 duplicate_student_id`로 응답한다.

## 4. 운영자 인증 (R-2)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/auth/admin/login` | 없음 | ID와 비밀번호 확인 |
| GET | `/auth/admin/state` | 설정 단계 세션 | 현재 단계 조회 |
| POST | `/auth/admin/password` | `PASSWORD_CHANGE`, `ACTIVE` | ID와 비밀번호 변경 |
| GET | `/auth/admin/otp/setup` | `OTP_SETUP` | OTP 등록 정보 발급 |
| POST | `/auth/admin/otp/setup` | `OTP_SETUP` | OTP 등록 완료 |
| POST | `/auth/admin/otp` | `OTP` | OTP 확인 |

```json
{ "username": "admin01", "password": "비밀번호" }
```

`/auth/admin/login`은 다음 단계를 돌려준다. ID가 없거나 비밀번호가 틀려도 같은 `401 invalid_credentials`로 응답한다.

```json
{ "stage": "OTP", "csrf_token": "토큰" }
```

| 요청 | 본문 |
|---|---|
| `POST /auth/admin/password` | `{ "current_password": "", "new_password": "", "new_username": "" }` |
| `POST /auth/admin/otp/setup`, `POST /auth/admin/otp` | `{ "code": "123456" }` |

`new_username`은 임시 계정의 첫 변경에서 받는다. `GET /auth/admin/otp/setup`은 `{ "secret": "", "otpauth_uri": "" }`를 돌려준다.

## 5. 프로젝트 (R-3, R-5)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/projects` | 학생, 운영자 | 목록 |
| POST | `/api/projects` | `STUDENT` | 등록, `201` |
| GET | `/api/projects/{id}` | 조회 권한 | 상세 |
| PATCH | `/api/projects/{id}` | 팀장, 운영자 | 수정 |
| POST | `/api/projects/{id}/archive` | 팀장, 운영자 | 보관, `204` |
| POST | `/api/projects/{id}/status` | 팀장, 운영자 | 진행 상태 변경, `204` |
| GET | `/api/projects/{id}/status-history` | 조회 권한 | 상태 이력 |

학생은 자기 프로젝트만 받고, 운영자는 전체 프로젝트를 받는다. 교수는 조회만 할 수 있다.

`GET /api/projects`의 조회 조건은 `q`, `status`, `include_archived`, `page`, `size`다.

```json
{
  "items": [
    {
      "id": 12,
      "name": "RepoScope",
      "status": "DEVELOPMENT",
      "my_role": "LEADER",
      "leader_name": "홍길동",
      "member_count": 4,
      "last_activity_at": "2026-11-07T01:23:45Z",
      "archived": false
    }
  ],
  "page": 1,
  "size": 50,
  "total": 1
}
```

운영자가 조회하면 `my_role`은 `null`이다.

### `POST /api/projects`

```json
{
  "name": "RepoScope",
  "description": "캡스톤 프로젝트 운영 서비스",
  "starts_on": "2026-10-12",
  "ends_on": "2026-11-30"
}
```

`description`, `starts_on`, `ends_on`은 선택이다. 등록한 학생은 팀장이 되고, 응답은 상세 응답과 같다. `PATCH`는 같은 필드 중 바꿀 값만 받는다.

### `GET /api/projects/{id}`

```json
{
  "id": 12,
  "name": "RepoScope",
  "description": "캡스톤 프로젝트 운영 서비스",
  "starts_on": "2026-10-12",
  "ends_on": "2026-11-30",
  "status": "DEVELOPMENT",
  "my_role": "LEADER",
  "archived": false,
  "can_edit": true,
  "created_at": "2026-10-20T01:00:00Z"
}
```

### `POST /api/projects/{id}/status`

```json
{ "status": "VERIFICATION", "memo": "기능 구현 완료" }
```

`status` 값은 `PLANNING`, `DEVELOPMENT`, `VERIFICATION`, `DONE`이다. `memo`는 선택이다.

## 6. 팀원 (R-4)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/projects/{id}/members` | 조회 권한 | 목록 |
| PATCH | `/api/projects/{id}/members/{user_id}` | 팀장, 운영자 | 역할 변경 |
| DELETE | `/api/projects/{id}/members/{user_id}` | 팀장, 운영자 | 제외, `204` |
| POST | `/api/projects/{id}/invites` | 팀장, 운영자 | 초대 코드 발급, `201` |
| POST | `/api/invites/accept` | `STUDENT` | 초대 코드로 참여 |

```json
{
  "items": [
    {
      "user_id": 7,
      "name": "홍길동",
      "student_id": "20261234",
      "login": "student-a",
      "role": "LEADER",
      "joined_at": "2026-10-20T01:00:00Z"
    }
  ]
}
```

역할 변경 본문은 `{ "role": "LEADER" }`이고, 값은 `LEADER` 또는 `MEMBER`다.

초대 코드 발급 응답의 `code`는 이 응답에서만 볼 수 있다.

```json
{ "code": "K7P4-X2MQ", "expires_at": "2026-11-14T01:00:00Z" }
```

참여 요청은 `{ "code": "K7P4-X2MQ" }`이고, 응답은 `{ "project_id": 12 }`다.

## 7. 결과물 (R-6)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/projects/{id}/deliverables` | 조회 권한 | 목록 |
| POST | `/api/projects/{id}/deliverables` | 팀원, 운영자 | 등록, `201` |
| PATCH | `/api/projects/{id}/deliverables/{deliverable_id}` | 등록자, 팀장, 운영자 | 수정 |
| DELETE | `/api/projects/{id}/deliverables/{deliverable_id}` | 등록자, 팀장, 운영자 | 삭제, `204` |

```json
{
  "kind": "SLIDES",
  "title": "최종 발표자료",
  "url": "https://example.com/slides"
}
```

`kind` 값은 `DOCUMENT`, `SLIDES`, `DEMO_VIDEO`, `REPOSITORY`, `OTHER`다. 응답에는 `id`, `created_by_name`, `created_at`을 추가한다.

## 8. 저장소 (R-7)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/projects/{id}/repos` | 조회 권한 | 연결된 저장소 |
| GET | `/api/projects/{id}/repo-candidates` | 팀장, 운영자 | 연결 가능한 저장소 |
| POST | `/api/projects/{id}/repos` | 팀장, 운영자 | 연결, `201` |
| DELETE | `/api/projects/{id}/repos/{repo_id}` | 팀장, 운영자 | 연결 해제, `204` |

```json
{
  "items": [
    {
      "repo_id": 987654,
      "full_name": "team/project",
      "is_private": true,
      "collect_status": "COLLECTING",
      "last_activity_at": "2026-11-07T01:23:45Z",
      "linked_at": "2026-10-21T01:00:00Z"
    }
  ]
}
```

| `collect_status` 값 | 화면 표시 |
|---|---|
| `BACKFILLING` | 과거 기록 가져오는 중 |
| `COLLECTING` | 수집 중 |
| `UNLINKED` | 연결 해제됨 |

`repo-candidates`는 로그인한 사용자의 접근 가능 저장소 중 수집 대상이고 다른 프로젝트에 연결되지 않은 저장소를 돌려준다.

```json
{
  "install_url": "https://github.com/apps/reposcope/installations/new",
  "items": [
    { "repo_id": 987654, "full_name": "team/project", "is_private": true }
  ]
}
```

접근 가능 저장소 목록은 로그인할 때 갱신한다. GitHub App을 설치한 뒤에는 화면이 `/auth/github/login?next=` 주소로 이동해 목록을 새로 받는다.

연결 요청은 `{ "repo_id": 987654 }`다. 후보에 없는 저장소는 `403 forbidden`, 이미 연결된 저장소는 `409 repo_already_linked`로 응답한다.

## 9. 활동 (R-8)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/projects/{id}/activities` | 조회 권한 | 활동 목록 |

조회 조건은 `from`, `to`, `kind`, `actor`, `repo_id`, `page`, `size`다. `from`과 `to`는 날짜이고 한국 시간 기준으로 해석한다. `kind` 값은 `COMMIT`, `PR`, `ISSUE`, `REVIEW`다.

```json
{
  "items": [
    {
      "id": 501,
      "kind": "PR",
      "ref": "42",
      "parent_ref": null,
      "title": "저장소 목록 화면 추가",
      "state": "merged",
      "actor_login": "student-a",
      "actor_name": "홍길동",
      "repo_full_name": "team/project",
      "occurred_at": "2026-11-06T01:23:45Z",
      "merged_at": "2026-11-06T05:00:00Z",
      "url": "https://github.com/team/project/pull/42"
    }
  ],
  "page": 1,
  "size": 50,
  "total": 1
}
```

프로젝트 팀원과 연결되지 않은 작성자는 `actor_name`이 `null`이고, 화면에 `미연결 작성자`로 표시한다.

## 10. 통계 (R-9)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/projects/{id}/stats` | 조회 권한 | 프로젝트 통계 |

조회 조건은 `from`, `to`다. 값이 없으면 프로젝트 전체 기간을 집계한다.

```json
{
  "summary": {
    "commits": 120,
    "pull_requests": 34,
    "issues": 21,
    "reviews": 40,
    "open_pull_requests": 3,
    "merged_pull_requests": 29,
    "last_activity_at": "2026-11-07T01:23:45Z"
  },
  "weekly": [
    {
      "week_start": "2026-11-02",
      "commits": 18,
      "pull_requests": 5,
      "merged_pull_requests": 4,
      "issues": 3,
      "reviews": 7
    }
  ],
  "members": [
    {
      "user_id": 7,
      "name": "홍길동",
      "login": "student-a",
      "pull_requests": 10,
      "reviews": 12,
      "issues": 6,
      "commits": 40
    }
  ],
  "unlinked_actors": {
    "pull_requests": 0,
    "reviews": 0,
    "issues": 0,
    "commits": 3
  }
}
```

`week_start`는 한국 시간 월요일이다. 활동이 없는 주도 0으로 포함한다.

## 11. 사용자 관리 (R-10)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/admin/users` | `SUPER_ADMIN`, `ADMIN` | 목록 |
| PATCH | `/api/admin/users/{id}` | `SUPER_ADMIN`, `ADMIN` | 학번, 이름, 학과 수정 |
| POST | `/api/admin/users/{id}/disable` | `SUPER_ADMIN`, `ADMIN` | 비활성화, `204` |
| POST | `/api/admin/users/{id}/enable` | `SUPER_ADMIN`, `ADMIN` | 비활성화 해제, `204` |

목록의 조회 조건은 `q`, `page`, `size`다. `q`는 이름, 학번, GitHub 로그인 이름을 검색한다.

```json
{
  "items": [
    {
      "id": 7,
      "name": "홍길동",
      "student_id": "20261234",
      "department": "컴퓨터공학과",
      "login": "student-a",
      "role": "STUDENT",
      "disabled": false,
      "registered_at": "2026-10-20T01:00:00Z"
    }
  ],
  "page": 1,
  "size": 50,
  "total": 1
}
```

`PATCH` 본문은 `name`, `student_id`, `department` 중 바꿀 값만 받는다.

## 12. 운영자 계정 (R-2)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/admin/operators` | `SUPER_ADMIN` | 목록 |
| POST | `/api/admin/operators` | `SUPER_ADMIN` | 계정 발급, `201` |
| PATCH | `/api/admin/operators/{id}` | `SUPER_ADMIN` | 직위, 이름, 비활성화 변경 |
| POST | `/api/admin/operators/{id}/reset-password` | `SUPER_ADMIN` | 임시 비밀번호 재발급 |
| POST | `/api/admin/operators/{id}/reset-otp` | `SUPER_ADMIN` | OTP 초기화, `204` |

계정 발급 요청이다. `role`은 `ADMIN` 또는 `PROFESSOR`다.

```json
{ "username": "admin02", "name": "담당자", "role": "ADMIN" }
```

임시 비밀번호는 발급 응답에서만 볼 수 있다.

```json
{
  "id": 21,
  "username": "admin02",
  "temp_password": "임시 비밀번호",
  "temp_password_expires_at": "2026-11-10T01:00:00Z"
}
```

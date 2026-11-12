-- DB 스키마 계약 (명세 7-2). PostgreSQL 17 (RDS, 로컬은 postgres:17-alpine)
--
-- 이 파일은 "계약"이다. 실제 적용은 B의 마이그레이션(backend/migrations/)이 하고, 그 결과가 이 파일과 같아야 한다.
--   - 테이블/컬럼 이름과 의미, 허용 값(CHECK), 키는 명세 7-2 그대로다. 바꾸려면 3인 승인 PR (CONTRIBUTING.md).
--   - 명세에 없던 세부(타입, NOT NULL, processed_deliveries.status 값, 인덱스)는 이 파일에서 처음 정했다.
--     B가 첫 마이그레이션 PR에서 확인하고, 다르게 하려면 이 파일도 같은 PR에서 고친다.
--   - C는 B의 마이그레이션 전에도 `sgd db contract`로 이 스키마를 로컬 DB에 만들어 API를 개발할 수 있다.
--
-- 공통 규칙 (명세 6-1, 7-2, 9장)
--   - repo는 이름이 아니라 숫자 repo_id(GitHub repository.id)로 식별한다. full_name은 바뀔 수 있다.
--   - 커밋 이메일, 코드, diff는 저장하지 않는다. activities.title은 커밋 메시지 첫 줄만.
--   - 이벤트 처리는 멱등: delivery_id 중복 방지 + 자연키 upsert. SQS는 중복/순서 뒤바뀜이 있으므로
--     installation_repositories가 installation.created보다 먼저 와도 처리되게 installations를 먼저 upsert한다.
--   - 시각은 모두 timestamptz(UTC).

-- ============================================================================ M1 (11/5)

CREATE TABLE installations (
    installation_id bigint      PRIMARY KEY,                       -- payload.installation.id
    account_login   text        NOT NULL,                          -- installation.account.login
    account_id      bigint      NOT NULL,                          -- installation.account.id
    account_type    text        NOT NULL CHECK (account_type IN ('User', 'Organization')),
    repo_selection  text        NOT NULL CHECK (repo_selection IN ('all', 'selected')),  -- installation.repository_selection
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    team_id         bigint      NULL,                              -- P0에서 teams(id) FK 추가
    installed_at    timestamptz NOT NULL,                          -- installation.created_at
    removed_at      timestamptz NULL                               -- installation.deleted 처리 시각
);

CREATE TABLE repos (
    repo_id         bigint      PRIMARY KEY,                       -- repository.id
    installation_id bigint      NOT NULL REFERENCES installations (installation_id),
    full_name       text        NOT NULL,                          -- owner/name (표시용, 키 아님)
    is_private      boolean     NOT NULL,
    status          text        NOT NULL CHECK (status IN ('LINKED', 'UNLINKED')),
    project_id      bigint      NULL,                              -- P0에서 projects(id) FK 추가. repo 하나는 프로젝트 하나에만
    last_synced_at  timestamptz NULL
);
CREATE INDEX repos_installation_id_idx ON repos (installation_id);

CREATE TABLE processed_deliveries (
    delivery_id  text        PRIMARY KEY,                          -- SQS 메시지 delivery_id (X-GitHub-Delivery)
    event        text        NOT NULL,
    received_at  timestamptz NOT NULL,                             -- SQS 메시지 received_at
    processed_at timestamptz NULL,
    status       text        NOT NULL CHECK (status IN ('PROCESSED', 'IGNORED', 'FAILED'))
    -- PROCESSED: 반영 완료 / IGNORED: 처리 대상 아닌 이벤트(M1의 push 등), 보관만 / FAILED: 재시도 한도 초과 기록용
);

-- ============================================================================ P0 (11/19)

CREATE TABLE users (
    id           bigserial   PRIMARY KEY,
    github_id    bigint      NOT NULL UNIQUE,
    login        text        NOT NULL,
    role         text        NOT NULL DEFAULT 'STUDENT' CHECK (role IN ('ADMIN', 'PROFESSOR', 'STUDENT')),
    consented_at timestamptz NULL                                  -- 수집/공개 범위 안내 동의 시각
);

CREATE TABLE teams (
    id         bigserial PRIMARY KEY,
    name       text      NOT NULL UNIQUE,                          -- 공개 화면에는 APPROVED만 노출 (9장)
    status     text      NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_by bigint    NOT NULL REFERENCES users (id)
);

CREATE TABLE team_members (
    team_id bigint NOT NULL REFERENCES teams (id),
    user_id bigint NOT NULL REFERENCES users (id),
    role    text   NOT NULL CHECK (role IN ('LEADER', 'MEMBER')),
    PRIMARY KEY (team_id, user_id)
);

CREATE TABLE projects (
    id        bigserial PRIMARY KEY,
    team_id   bigint    NOT NULL REFERENCES teams (id),
    title     text      NOT NULL,
    status    text      NOT NULL,                                  -- 값 목록은 C가 P0에서 확정
    starts_on date      NULL,
    ends_on   date      NULL
);

ALTER TABLE installations ADD CONSTRAINT installations_team_id_fkey FOREIGN KEY (team_id) REFERENCES teams (id);
ALTER TABLE repos ADD CONSTRAINT repos_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects (id);

CREATE TABLE activities (
    id              bigserial   PRIMARY KEY,
    repo_id         bigint      NOT NULL REFERENCES repos (repo_id),
    kind            text        NOT NULL CHECK (kind IN ('COMMIT', 'PR', 'ISSUE')),
    ref             text        NOT NULL,                          -- COMMIT: SHA, PR/ISSUE: 번호
    actor_github_id bigint      NULL,
    actor_login     text        NULL,
    title           text        NULL,                              -- 커밋은 메시지 첫 줄만
    state           text        NULL,                              -- PR: open/closed/merged, ISSUE: open/closed
    occurred_at     timestamptz NOT NULL,
    url             text        NULL,
    UNIQUE (repo_id, kind, ref)                                    -- 같은 키는 최신 상태로 upsert
);
CREATE INDEX activities_repo_occurred_idx ON activities (repo_id, occurred_at);

CREATE TABLE audit_logs (
    id            bigserial   PRIMARY KEY,
    actor_user_id bigint      NULL REFERENCES users (id),
    action        text        NOT NULL,                            -- 예: TEAM_APPROVE, TEAM_REJECT, DATA_DELETE, VIEW_PROJECT
    target        text        NOT NULL,                            -- 예: team:12, project:3
    at            timestamptz NOT NULL DEFAULT now()
);

-- ============================================================================ P1 (11/26, backfill은 11/4 앞당김)

-- repo별 과거 활동 가져오기 작업 (worker만 읽고 쓴다). repo가 LINKED가 되면 PENDING으로 생기고,
-- worker가 GitHub API로 커밋(모든 브랜치)·PR·이슈를 읽어 activities에 upsert한 뒤 DONE이 된다.
--   - since: 이 시각 이후만 가져옴. NULL이면 전체 기록 (BACKFILL_DAYS 미설정)
--   - SKIPPED: repo가 LINKED가 아니거나 '전체 저장소(all)'로 설치된 경우 (과거 기록은 가져오지 않음)
--   - FAILED: 저장소/설치가 없음(404 등)이거나 다시 시도해도 실패
--   - last_error: 원인 코드만 (http_404, network 등). GitHub 응답 본문은 저장하지 않는다
CREATE TABLE backfill_jobs (
    repo_id         bigint      PRIMARY KEY REFERENCES repos (repo_id) ON DELETE CASCADE,
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'SKIPPED', 'FAILED')),
    since           timestamptz NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    items           integer     NULL,
    last_error      text        NULL,
    requested_at    timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz NULL
);
CREATE INDEX backfill_jobs_due_idx ON backfill_jobs (next_attempt_at) WHERE status = 'PENDING';

-- 사업·프로그램 (11/4 관리 화면 요구사항: 연도별 사업·프로그램 관리, 프로그램별 통계)
-- 지금은 참여자가 로그인하지 않으므로 관리자가 저장소마다 프로그램을 지정한다 (repos.program_id).
-- 학생 흐름(P0: 팀·프로젝트)이 생기면 projects와의 관계는 그때 정한다.
CREATE TABLE programs (
    id         bigserial   PRIMARY KEY,
    year       integer     NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    business   text        NOT NULL,                            -- 사업명 (예: SW중심대학)
    name       text        NOT NULL,                            -- 프로그램명
    is_open    boolean     NOT NULL DEFAULT true,               -- 모집 중
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (year, business, name)
);
ALTER TABLE repos ADD COLUMN program_id bigint NULL REFERENCES programs (id);
CREATE INDEX repos_program_id_idx ON repos (program_id);

-- 사용자별 접근 가능 저장소 (11/4, 참여자가 자기 저장소를 직접 프로그램에 지정)
-- 로그인할 때 사용자 토큰으로 GET /user/installations, /user/installations/{id}/repositories를 읽어 통째로 바꾼다.
-- GitHub 사용자 토큰은 저장하지 않는다. repo_id는 아직 repos에 없을 수도 있어 FK를 두지 않는다.
CREATE TABLE user_repos (
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repo_id    bigint      NOT NULL,
    checked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, repo_id)
);
CREATE INDEX user_repos_repo_id_idx ON user_repos (repo_id);

-- 회원가입 정보 (11/4, 참여자가 처음 로그인할 때 입력해야 가입이 끝난다). 개인정보:
-- 운영자·교수 화면(관리자 API)에서만 보이고 공개 화면, 공개 JSON, 로그에는 쓰지 않는다.
--   - student_id: 숫자 6~12자리, 한 학번은 한 계정만 (UNIQUE, NULL은 여러 개 가능)
--   - registered_at: 가입 정보를 처음 저장한 시각
ALTER TABLE users ADD COLUMN name text NULL;
ALTER TABLE users ADD COLUMN student_id text NULL;
ALTER TABLE users ADD COLUMN department text NULL;
ALTER TABLE users ADD COLUMN registered_at timestamptz NULL;
ALTER TABLE users ADD CONSTRAINT users_student_id_key UNIQUE (student_id);

-- 운영자 ID 계정과 직위 5단계 (11/4)
-- 직위: SUPER_ADMIN(최고관리자) > ADMIN(관리자, 사업단) > PROFESSOR(교수) > STUDENT(학생) > GUEST(일반)
--   - GitHub 계정(github_id 있음): 가입할 때 학생/일반을 고른다. 관리자가 교수·관리자로 올릴 수 있다 (최고관리자 제외)
--   - 운영자 ID 계정(github_id 없음, operator_credentials): 최고관리자가 만들고 임시 비밀번호를 발급한다.
--     ID/PW + OTP(TOTP)로만 로그인. 첫 최고관리자는 'sgd admin bootstrap'으로 만든다
-- 비밀번호는 scrypt 해시만 저장한다. OTP 비밀키는 SESSION_SECRET에서 만든 키로 암호화(AES-GCM)해 저장한다.
-- operator_sessions.id_hash: 세션 토큰의 SHA-256 (토큰 자체는 저장하지 않음). 로그아웃·비밀번호 변경 때 지운다.
-- login_attempts: IP별 로그인 실패 기록 (15분 동안 20번 넘으면 그 IP는 잠시 막음). 하루 지난 것은 지운다.
ALTER TABLE users ALTER COLUMN github_id DROP NOT NULL;
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'GUEST';
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'PROFESSOR', 'STUDENT', 'GUEST'));

CREATE TABLE operator_credentials (
    user_id                  bigint      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    username                 text        NOT NULL UNIQUE,              -- 로그인 ID (소문자)
    password_hash            text        NOT NULL,                     -- scrypt$<log2 N>$<r>$<p>$<salt>$<hash>
    must_change_password     boolean     NOT NULL DEFAULT true,        -- 임시 비밀번호면 true (로그인하면 바꿔야 함)
    temp_password_expires_at timestamptz NULL,                         -- 임시 비밀번호 만료 (발급 72시간 뒤)
    totp_secret              text        NULL,                         -- 암호화한 OTP 비밀키 (등록 전 NULL)
    totp_enabled_at          timestamptz NULL,
    totp_last_step           bigint      NULL,                         -- 마지막으로 쓴 OTP 시간 단계 (재사용 방지)
    failed_attempts          integer     NOT NULL DEFAULT 0,           -- 연속 비밀번호 실패 (5번이면 15분 잠금)
    otp_failed_attempts      integer     NOT NULL DEFAULT 0,           -- 연속 OTP 실패 (비밀번호가 맞아도 줄지 않음, 5번이면 잠금)
    locked_until             timestamptz NULL,
    disabled_at              timestamptz NULL,
    password_changed_at      timestamptz NULL,
    last_login_at            timestamptz NULL,
    created_by               bigint      NULL REFERENCES users (id),
    created_at               timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE operator_sessions (
    id_hash      text        PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stage        text        NOT NULL CHECK (stage IN ('PASSWORD_CHANGE', 'OTP_SETUP', 'OTP', 'ACTIVE')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),                   -- 30분 동안 요청이 없으면 끝
    expires_at   timestamptz NOT NULL,                                 -- 로그인 8시간 뒤 끝
    ip           text        NULL
);
CREATE INDEX operator_sessions_user_id_idx ON operator_sessions (user_id);

CREATE TABLE login_attempts (
    ip text        NOT NULL,
    at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_attempts_ip_at_idx ON login_attempts (ip, at);

-- 운영자 ID 계정 사용 기한과 삭제 (11/5)
--   - expires_at: 이 시각부터 로그인할 수 없고 쓰던 세션도 끝난다. NULL이면 기한 없음 (최고관리자 계정은 늘 NULL).
--     최고관리자가 마지막 사용일(한국 시간, 오늘부터 3년 안)을 정하면 그다음 날 0시(Asia/Seoul)가 된다.
--   - 계정을 지우면 operator_credentials·operator_sessions 행은 지우고, users 행은 audit_logs.actor_user_id가 가리키므로
--     남긴다: role 'GUEST', login 'deleted:<id>' (운영자 ID·GitHub 로그인에 없는 모양), name·student_id·department NULL, github_id NULL
--     (로그인할 수 없고 어느 목록에도 안 보임).
--     지운 ID는 audit_logs(OPERATOR_DELETED, target 'operator:<id> username:<ID>')에 남는다.
ALTER TABLE operator_credentials ADD COLUMN expires_at timestamptz NULL;

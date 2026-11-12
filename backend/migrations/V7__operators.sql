-- 운영자 ID 계정(ID/PW + OTP), 직위 5단계 (11/4): docs/contracts/db-schema.sql P1 section, unchanged.
ALTER TABLE users ALTER COLUMN github_id DROP NOT NULL;
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'GUEST';
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'PROFESSOR', 'STUDENT', 'GUEST'));

CREATE TABLE operator_credentials (
    user_id                  bigint      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    username                 text        NOT NULL UNIQUE,
    password_hash            text        NOT NULL,
    must_change_password     boolean     NOT NULL DEFAULT true,
    temp_password_expires_at timestamptz NULL,
    totp_secret              text        NULL,
    totp_enabled_at          timestamptz NULL,
    totp_last_step           bigint      NULL,
    failed_attempts          integer     NOT NULL DEFAULT 0,
    otp_failed_attempts      integer     NOT NULL DEFAULT 0,
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
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    ip           text        NULL
);
CREATE INDEX operator_sessions_user_id_idx ON operator_sessions (user_id);

CREATE TABLE login_attempts (
    ip text        NOT NULL,
    at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_attempts_ip_at_idx ON login_attempts (ip, at);

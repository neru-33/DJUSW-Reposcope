-- capstone 스키마 테이블 (docs/data-model.md 4장, 7장)
-- 스키마 이름은 CAPSTONE_SCHEMA 환경 변수로 정한다 (Flyway defaultSchema).
-- users.id, repos.repo_id를 가리키는 컬럼에는 외래 키를 걸지 않는다.

CREATE TABLE ${flyway:defaultSchema}.projects (
    id          bigserial   PRIMARY KEY,
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    description text        NULL CHECK (char_length(description) <= 500),
    starts_on   date        NULL,
    ends_on     date        NULL,
    status      text        NOT NULL DEFAULT 'PLANNING'
                            CHECK (status IN ('PLANNING', 'DEVELOPMENT', 'VERIFICATION', 'DONE')),
    program_id  bigint      NULL,
    archived_at timestamptz NULL,
    created_by  bigint      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT projects_period_check CHECK (ends_on >= starts_on)
);

CREATE TABLE ${flyway:defaultSchema}.project_members (
    project_id bigint      NOT NULL REFERENCES ${flyway:defaultSchema}.projects (id),
    user_id    bigint      NOT NULL,
    role       text        NOT NULL CHECK (role IN ('LEADER', 'MEMBER')),
    joined_at  timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz NULL,
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX project_members_user_id_idx ON ${flyway:defaultSchema}.project_members (user_id);

CREATE TABLE ${flyway:defaultSchema}.project_invites (
    id         bigserial   PRIMARY KEY,
    project_id bigint      NOT NULL REFERENCES ${flyway:defaultSchema}.projects (id),
    code_hash  text        NOT NULL UNIQUE,
    created_by bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL
);
-- 프로젝트별 유효한 초대 코드는 하나만 둔다.
CREATE UNIQUE INDEX project_invites_active_idx
    ON ${flyway:defaultSchema}.project_invites (project_id) WHERE revoked_at IS NULL;

CREATE TABLE ${flyway:defaultSchema}.project_status_history (
    id          bigserial   PRIMARY KEY,
    project_id  bigint      NOT NULL REFERENCES ${flyway:defaultSchema}.projects (id),
    from_status text        NULL
                            CHECK (from_status IN ('PLANNING', 'DEVELOPMENT', 'VERIFICATION', 'DONE')),
    to_status   text        NOT NULL
                            CHECK (to_status IN ('PLANNING', 'DEVELOPMENT', 'VERIFICATION', 'DONE')),
    changed_by  bigint      NOT NULL,
    memo        text        NULL,
    changed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_status_history_project_changed_idx
    ON ${flyway:defaultSchema}.project_status_history (project_id, changed_at);

CREATE TABLE ${flyway:defaultSchema}.deliverables (
    id         bigserial   PRIMARY KEY,
    project_id bigint      NOT NULL REFERENCES ${flyway:defaultSchema}.projects (id),
    kind       text        NOT NULL CHECK (kind IN ('DOCUMENT', 'SLIDES', 'DEMO_VIDEO', 'REPOSITORY', 'OTHER')),
    title      text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 100),
    url        text        NOT NULL CHECK (url LIKE 'https://%'),
    created_by bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX deliverables_project_id_idx ON ${flyway:defaultSchema}.deliverables (project_id);

CREATE TABLE ${flyway:defaultSchema}.project_repos (
    id             bigserial   PRIMARY KEY,
    project_id     bigint      NOT NULL REFERENCES ${flyway:defaultSchema}.projects (id),
    github_repo_id bigint      NOT NULL,
    linked_by      bigint      NOT NULL,
    linked_at      timestamptz NOT NULL DEFAULT now(),
    unlinked_at    timestamptz NULL
);
-- 저장소 하나는 한 프로젝트에만 연결한다.
CREATE UNIQUE INDEX project_repos_active_idx
    ON ${flyway:defaultSchema}.project_repos (github_repo_id) WHERE unlinked_at IS NULL;

CREATE TABLE ${flyway:defaultSchema}.audit_logs (
    id            bigserial   PRIMARY KEY,
    actor_user_id bigint      NOT NULL,
    action        text        NOT NULL,
    target        text        NOT NULL,
    at            timestamptz NOT NULL DEFAULT now()
);

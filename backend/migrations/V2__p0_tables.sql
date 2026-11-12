-- P0 tables: docs/contracts/db-schema.sql (7-2) P0 section, unchanged.
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

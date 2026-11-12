-- 사용자별 접근 가능 저장소 (11/4, 참여자가 직접 프로그램 지정): docs/contracts/db-schema.sql P1 section, unchanged.
CREATE TABLE user_repos (
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repo_id    bigint      NOT NULL,
    checked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, repo_id)
);
CREATE INDEX user_repos_repo_id_idx ON user_repos (repo_id);

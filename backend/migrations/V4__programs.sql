-- 사업·프로그램 (11/4 관리 화면 요구사항): docs/contracts/db-schema.sql P1 section, unchanged.
CREATE TABLE programs (
    id         bigserial   PRIMARY KEY,
    year       integer     NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    business   text        NOT NULL,
    name       text        NOT NULL,
    is_open    boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (year, business, name)
);
ALTER TABLE repos ADD COLUMN program_id bigint NULL REFERENCES programs (id);
CREATE INDEX repos_program_id_idx ON repos (program_id);

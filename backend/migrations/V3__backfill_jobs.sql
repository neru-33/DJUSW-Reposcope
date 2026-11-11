-- P1 backfill (11/4 앞당김): docs/contracts/db-schema.sql P1 section, unchanged.
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

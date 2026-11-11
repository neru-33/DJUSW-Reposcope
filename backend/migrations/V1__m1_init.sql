-- M1 domain tables: docs/contracts/db-schema.sql (7-2).
CREATE TABLE installations (
    installation_id bigint PRIMARY KEY,
    account_login text NOT NULL,
    account_id bigint NOT NULL,
    account_type text NOT NULL CHECK (account_type IN ('User', 'Organization')),
    repo_selection text NOT NULL CHECK (repo_selection IN ('all', 'selected')),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    team_id bigint NULL,
    installed_at timestamptz NOT NULL,
    removed_at timestamptz NULL
);

CREATE TABLE repos (
    repo_id bigint PRIMARY KEY,
    installation_id bigint NOT NULL REFERENCES installations (installation_id),
    full_name text NOT NULL,
    is_private boolean NOT NULL,
    status text NOT NULL CHECK (status IN ('LINKED', 'UNLINKED')),
    project_id bigint NULL,
    last_synced_at timestamptz NULL
);
CREATE INDEX repos_installation_id_idx ON repos (installation_id);

CREATE TABLE processed_deliveries (
    delivery_id text PRIMARY KEY,
    event text NOT NULL,
    received_at timestamptz NOT NULL,
    processed_at timestamptz NULL,
    status text NOT NULL CHECK (status IN ('PROCESSED', 'IGNORED', 'FAILED'))
);

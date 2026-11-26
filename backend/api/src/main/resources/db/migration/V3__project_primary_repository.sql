ALTER TABLE ${flyway:defaultSchema}.project_repos
    ADD COLUMN is_primary boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX project_repos_active_primary_idx
    ON ${flyway:defaultSchema}.project_repos (project_id)
    WHERE is_primary AND unlinked_at IS NULL;

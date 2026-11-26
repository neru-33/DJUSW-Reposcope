ALTER TABLE ${flyway:defaultSchema}.projects
    ADD COLUMN team_name text CHECK (char_length(team_name) <= 50),
    ADD COLUMN visibility text NOT NULL DEFAULT 'TEAM' CHECK (visibility IN ('TEAM', 'STUDENTS'));

CREATE INDEX projects_public_visibility_idx ON ${flyway:defaultSchema}.projects (visibility)
    WHERE archived_at IS NULL;

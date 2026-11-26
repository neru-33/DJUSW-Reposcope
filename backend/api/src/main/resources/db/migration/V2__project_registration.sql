ALTER TABLE ${flyway:defaultSchema}.projects
    ADD COLUMN team_name text CHECK (char_length(team_name) <= 50),
    ADD COLUMN visibility text NOT NULL DEFAULT 'TEAM' CHECK (visibility IN ('TEAM', 'STUDENTS'));

ALTER TABLE ${flyway:defaultSchema}.projects DROP CONSTRAINT projects_period_check;
-- 기존 데이터를 변경하지 않고 이후에 저장되는 날짜부터 엄격하게 검사한다.
ALTER TABLE ${flyway:defaultSchema}.projects ADD CONSTRAINT projects_period_check
    CHECK (ends_on > starts_on) NOT VALID;

CREATE INDEX projects_public_visibility_idx ON ${flyway:defaultSchema}.projects (visibility)
    WHERE archived_at IS NULL;

-- 운영자 ID 계정 사용 기한 (11/5): docs/contracts/db-schema.sql P1 section, unchanged.
ALTER TABLE operator_credentials ADD COLUMN expires_at timestamptz NULL;

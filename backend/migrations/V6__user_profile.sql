-- 회원가입 정보 (11/4, 참여자 가입 때 이름·학번·학과): docs/contracts/db-schema.sql P1 section, unchanged.
ALTER TABLE users ADD COLUMN name text NULL;
ALTER TABLE users ADD COLUMN student_id text NULL;
ALTER TABLE users ADD COLUMN department text NULL;
ALTER TABLE users ADD COLUMN registered_at timestamptz NULL;
ALTER TABLE users ADD CONSTRAINT users_student_id_key UNIQUE (student_id);

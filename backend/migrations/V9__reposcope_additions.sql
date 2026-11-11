-- RepoScope 추가분 (docs/data-model.md 5장, 6장, 7장). V1~V8은 2026URP 계약과 같다.

-- 리뷰 수집: kind에 REVIEW 허용, 리뷰가 속한 PR 번호, PR 병합 시각
ALTER TABLE activities DROP CONSTRAINT activities_kind_check;
ALTER TABLE activities ADD CONSTRAINT activities_kind_check
    CHECK (kind IN ('COMMIT', 'PR', 'ISSUE', 'REVIEW'));
ALTER TABLE activities ADD COLUMN parent_ref text NULL;          -- 리뷰가 속한 PR 번호. 리뷰가 아니면 NULL
ALTER TABLE activities ADD COLUMN merged_at timestamptz NULL;    -- PR 병합 시각. PR이 아니면 NULL

-- 팀원별 통계
CREATE INDEX activities_repo_actor_idx ON activities (repo_id, actor_github_id);

-- 사용자 비활성화, 학번 형식 (숫자 6~12자리)
ALTER TABLE users ADD COLUMN disabled_at timestamptz NULL;
ALTER TABLE users ADD CONSTRAINT users_student_id_format_check
    CHECK (student_id ~ '^[0-9]{6,12}$');

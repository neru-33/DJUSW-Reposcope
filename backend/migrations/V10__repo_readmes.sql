-- RepoScope 추가분 (docs/data-model.md 5장 repo_readmes). 저장소의 README. README가 없으면 행이 없다.
CREATE TABLE repo_readmes (
    repo_id    bigint      PRIMARY KEY REFERENCES repos (repo_id) ON DELETE CASCADE,
    path       text        NOT NULL,                                          -- 저장소 안 README 경로
    content    text        NOT NULL CHECK (octet_length(content) <= 524288),  -- Markdown 본문, 512KB 이하
    etag       text        NULL,                                              -- 조건부 요청에 쓰는 ETag
    fetched_at timestamptz NOT NULL DEFAULT now()                             -- 마지막으로 가져온 시각
);

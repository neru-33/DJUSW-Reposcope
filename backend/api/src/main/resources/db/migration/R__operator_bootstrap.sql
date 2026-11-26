-- 반복 적용 시 기존 초기화 기록을 유지한다.
CREATE TABLE IF NOT EXISTS ${flyway:defaultSchema}.operator_bootstrap (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    completed_at timestamptz NOT NULL DEFAULT now()
);

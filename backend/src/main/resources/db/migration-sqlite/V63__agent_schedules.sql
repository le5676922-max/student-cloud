CREATE TABLE ai_schedule (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id INTEGER NOT NULL,
    title VARCHAR(100) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(100) NOT NULL,
    directory_path VARCHAR(500) NOT NULL,
    course_id INTEGER,
    tags VARCHAR(200),
    day_of_week INTEGER NOT NULL,
    hour_of_day INTEGER NOT NULL,
    minute_of_hour INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    next_run_at BIGINT NOT NULL,
    last_run_at BIGINT,
    last_error VARCHAR(500)
);
CREATE INDEX idx_ai_schedule_due ON ai_schedule(enabled, next_run_at);
CREATE INDEX idx_ai_schedule_owner ON ai_schedule(owner_user_id);

CREATE TABLE ai_task (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    steps_json TEXT NOT NULL,
    results_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_ai_task_owner_created ON ai_task(owner_user_id, created_at);

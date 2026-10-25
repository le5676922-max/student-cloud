CREATE TABLE ai_answer_feedback (
    id TEXT NOT NULL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    tenant_id INTEGER NULL,
    client_message_id TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    page_path TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'OPEN',
    corrected_answer TEXT NOT NULL,
    note TEXT NOT NULL,
    reviewed_by INTEGER NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (user_id, client_message_id),
    FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE SET NULL,
    FOREIGN KEY (reviewed_by) REFERENCES `user` (id) ON DELETE SET NULL
);
CREATE INDEX idx_ai_feedback_status_created ON ai_answer_feedback (status, created_at, id);
CREATE INDEX idx_ai_feedback_user_created ON ai_answer_feedback (user_id, created_at);

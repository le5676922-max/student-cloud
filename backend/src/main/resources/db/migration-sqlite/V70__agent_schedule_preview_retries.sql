ALTER TABLE ai_schedule ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_schedule ADD COLUMN retry_at BIGINT NULL;
ALTER TABLE ai_schedule ADD COLUMN claim_token VARCHAR(36) NULL;
ALTER TABLE ai_schedule ADD COLUMN claim_until BIGINT NULL;
ALTER TABLE ai_schedule ADD COLUMN occurrence_task_id VARCHAR(36) NULL;
ALTER TABLE ai_schedule ADD COLUMN last_task_id VARCHAR(36) NULL;
CREATE INDEX idx_ai_schedule_retry ON ai_schedule(enabled, retry_at);

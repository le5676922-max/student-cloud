-- One schedule row represents a weekly preview trigger only; it never stores executable writes.
CREATE TABLE sc_agent_schedule_guard (
  id INTEGER NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO sc_agent_schedule_guard(id, revision) VALUES (1, 0);

CREATE TABLE sc_agent_schedule (
  schedule_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_account_id BIGINT NOT NULL,
  workspace_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  storage_key VARCHAR(128) NOT NULL,
  directory_path VARCHAR(500) NOT NULL,
  course_id BIGINT,
  tags VARCHAR(200),
  day_of_week INTEGER NOT NULL,
  hour_of_day INTEGER NOT NULL,
  minute_of_hour INTEGER NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  next_run_at BIGINT,
  last_run_at BIGINT,
  last_error VARCHAR(500),
  preview_state VARCHAR(32) NOT NULL,
  retry_at BIGINT,
  retry_count INTEGER NOT NULL DEFAULT 0,
  last_task_id VARCHAR(36),
  claim_token VARCHAR(36),
  claim_started_at BIGINT,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX sc_agent_schedule_due_idx ON sc_agent_schedule(enabled, next_run_at, schedule_id);
CREATE INDEX sc_agent_schedule_owner_idx ON sc_agent_schedule(owner_account_id, created_at, schedule_id);

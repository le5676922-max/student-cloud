CREATE TABLE sc_agent_plan (
  plan_id VARCHAR(36) NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  workspace_id BIGINT,
  storage_key VARCHAR(128) NOT NULL,
  preview_token VARCHAR(256) NOT NULL,
  steps_json TEXT NOT NULL,
  expires_at BIGINT NOT NULL,
  job_id VARCHAR(36),
  state VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX sc_agent_plan_owner_idx ON sc_agent_plan(account_id,created_at);

CREATE TABLE sc_agent_command_guard (id INTEGER PRIMARY KEY);
INSERT INTO sc_agent_command_guard(id) VALUES(1);
CREATE TABLE sc_agent_command (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  preview_hash CHAR(64) NOT NULL UNIQUE,
  actor_id BIGINT NOT NULL,
  workspace_id BIGINT,
  mount_key VARCHAR(64) NOT NULL,
  command_json TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  idempotency_key VARCHAR(120),
  result_json TEXT,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  started_at BIGINT,
  finished_at BIGINT,
  UNIQUE(actor_id,idempotency_key)
);
CREATE INDEX sc_agent_command_actor_idx ON sc_agent_command(actor_id,created_at);

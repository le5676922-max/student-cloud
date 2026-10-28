CREATE TABLE sc_upload_session (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  workspace_id BIGINT,
  node_id VARCHAR(64) NOT NULL,
  mount_key VARCHAR(64) NOT NULL,
  target_path VARCHAR(4096) NOT NULL,
  byte_size BIGINT NOT NULL,
  staging_reserved BIGINT NOT NULL,
  manifest_hash VARCHAR(64) NOT NULL,
  authorization_hash VARCHAR(64) NOT NULL,
  staging_identity VARCHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempt_token VARCHAR(36),
  attempt_until BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL
);
CREATE INDEX sc_upload_owner_idx ON sc_upload_session(owner_id,state,expires_at);
CREATE INDEX sc_upload_expiry_idx ON sc_upload_session(node_id,expires_at);
CREATE TABLE sc_upload_chunk (
  session_id VARCHAR(36) NOT NULL,
  chunk_index INTEGER NOT NULL,
  expected_hash VARCHAR(64) NOT NULL,
  file_name VARCHAR(64),
  PRIMARY KEY(session_id,chunk_index),
  CONSTRAINT sc_upload_chunk_session_fk FOREIGN KEY(session_id) REFERENCES sc_upload_session(id)
);

ALTER TABLE sc_workspace ADD COLUMN ownership_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE sc_workspace_ownership_transfer (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  resource_id BIGINT NOT NULL,
  from_account_id BIGINT NOT NULL,
  to_account_id BIGINT NOT NULL,
  expected_revision BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  expires_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  completed_at BIGINT,
  idempotency_key VARCHAR(100) NOT NULL,
  CONSTRAINT sc_workspace_transfer_key UNIQUE(from_account_id,idempotency_key),
  CONSTRAINT sc_workspace_transfer_resource_fk FOREIGN KEY(resource_id) REFERENCES sc_workspace(id)
);
CREATE INDEX sc_workspace_transfer_pending ON sc_workspace_ownership_transfer(resource_id,state,expires_at);

-- Content migration V15. Evidence is append-only and independent of file deletion.
-- Initial policy: no automatic deletion/expiry; reviewed retention requires a new migration.
CREATE TABLE sc_storage_operation (
  row_no BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  id VARCHAR(36) NOT NULL UNIQUE,
  actor_id BIGINT NOT NULL,
  workspace_id BIGINT,
  mount_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  idempotency_hash VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  result_ref VARCHAR(36),
  error_code VARCHAR(40),
  attempt INTEGER NOT NULL DEFAULT 0,
  fence BIGINT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT sc_storage_operation_idempotency_uq UNIQUE(actor_id,type,idempotency_hash),
  CONSTRAINT sc_storage_operation_state_ck CHECK(CASE state WHEN 'PREPARED' THEN 1 WHEN 'RUNNING' THEN 1 WHEN 'COMMITTING' THEN 1 WHEN 'SUCCEEDED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'CANCELLED' THEN 1 WHEN 'INDETERMINATE' THEN 1 ELSE 0 END = 1),
  CONSTRAINT sc_storage_operation_fence_ck CHECK(fence >= 0 AND attempt >= 0 AND revision >= 1)
);
CREATE INDEX sc_storage_operation_owner_idx ON sc_storage_operation(actor_id,mount_id,row_no);

CREATE TABLE sc_storage_operation_event (
  operation_id VARCHAR(36) NOT NULL,
  sequence_no BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  fence BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY(operation_id,sequence_no),
  CONSTRAINT sc_storage_operation_event_fk FOREIGN KEY(operation_id) REFERENCES sc_storage_operation(id)
);

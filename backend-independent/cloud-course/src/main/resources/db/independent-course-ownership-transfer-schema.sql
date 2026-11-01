ALTER TABLE sc_shared_course ADD COLUMN ownership_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE sc_course_ownership_transfer (
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
  CONSTRAINT sc_course_transfer_key UNIQUE(from_account_id,idempotency_key),
  CONSTRAINT sc_course_transfer_resource_fk FOREIGN KEY(resource_id) REFERENCES sc_shared_course(id)
);
CREATE INDEX sc_course_transfer_pending ON sc_course_ownership_transfer(resource_id,state,expires_at);

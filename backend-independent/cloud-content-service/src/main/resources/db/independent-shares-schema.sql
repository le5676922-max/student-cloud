CREATE TABLE sc_share_owner_guard (account_id BIGINT PRIMARY KEY);
CREATE TABLE sc_file_share (
  share_id VARCHAR(43) PRIMARY KEY,
  owner_account BIGINT NOT NULL,
  workspace_id BIGINT NOT NULL,
  mount_key VARCHAR(64) NOT NULL,
  source_path VARCHAR(4096) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  modified_at BIGINT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  audience VARCHAR(20) NOT NULL,
  course_id BIGINT,
  expires_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  revoked_at BIGINT,
  version BIGINT NOT NULL,
  CONSTRAINT ck_share_audience CHECK ((audience='OWN_WORKSPACE' AND course_id IS NULL) OR (audience='COURSE' AND course_id IS NOT NULL)),
  CONSTRAINT ck_share_version CHECK (version>0)
);
CREATE INDEX ix_share_owner_active ON sc_file_share(owner_account,revoked_at,expires_at);
CREATE INDEX ix_share_owner_created ON sc_file_share(owner_account,created_at);

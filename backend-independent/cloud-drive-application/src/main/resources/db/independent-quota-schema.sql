CREATE TABLE sc_quota_account (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  mount_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  owner_scope VARCHAR(128) NOT NULL,
  limit_bytes BIGINT,
  used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
  reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
  revision BIGINT NOT NULL DEFAULT 1,
  usage_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  reconciled_at BIGINT,
  UNIQUE (mount_id, owner_id, owner_scope)
);
CREATE TABLE sc_quota_reservation (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  quota_account_id VARCHAR(32) NOT NULL,
  operation_id VARCHAR(128) NOT NULL,
  max_bytes BIGINT NOT NULL CHECK (max_bytes >= 0),
  actual_bytes BIGINT,
  state VARCHAR(16) NOT NULL,
  expires_at BIGINT NOT NULL,
  UNIQUE (quota_account_id, operation_id),
  FOREIGN KEY (quota_account_id) REFERENCES sc_quota_account(id)
);
CREATE TABLE sc_quota_entry (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  operation_id VARCHAR(128) NOT NULL,
  quota_account_id VARCHAR(32) NOT NULL,
  file_version_id VARCHAR(64),
  category VARCHAR(20) NOT NULL,
  delta_bytes BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE (quota_account_id, operation_id, category),
  FOREIGN KEY (quota_account_id) REFERENCES sc_quota_account(id)
);

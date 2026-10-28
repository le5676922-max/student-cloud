CREATE TABLE sc_drive_trash_guard (actor_id BIGINT NOT NULL PRIMARY KEY);
CREATE TABLE sc_drive_trash (
  id CHAR(32) NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  workspace_id BIGINT,
  mount_id BIGINT NOT NULL,
  scope_hash CHAR(64) NOT NULL,
  grant_root VARCHAR(1024) NOT NULL,
  original_path VARCHAR(4096) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  is_directory BOOLEAN NOT NULL,
  byte_size BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX sc_drive_trash_owner ON sc_drive_trash(actor_id,mount_id,scope_hash,status,created_at);
CREATE INDEX sc_drive_trash_expiry ON sc_drive_trash(status,expires_at);

CREATE TABLE sc_file_catalog_owner (
  actor_id BIGINT NOT NULL PRIMARY KEY
);
CREATE TABLE sc_file_catalog_mount (
  mount_id BIGINT NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE sc_file_catalog_directory (
  actor_id BIGINT NOT NULL,
  mount_id BIGINT NOT NULL,
  scope_hash VARCHAR(64) NOT NULL,
  parent_hash VARCHAR(64) NOT NULL,
  parent_path VARCHAR(4096) NOT NULL,
  generation VARCHAR(36) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  mount_revision BIGINT NOT NULL,
  parent_modified BIGINT NOT NULL,
  refreshed_at BIGINT NOT NULL,
  valid BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY(actor_id,mount_id,scope_hash,parent_hash)
);
CREATE INDEX sc_file_catalog_owner_time_idx ON sc_file_catalog_directory(actor_id,refreshed_at);
CREATE INDEX sc_file_catalog_mount_idx ON sc_file_catalog_directory(mount_id);
CREATE TABLE sc_file_catalog_entry (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  mount_id BIGINT NOT NULL,
  scope_hash VARCHAR(64) NOT NULL,
  parent_hash VARCHAR(64) NOT NULL,
  path_hash VARCHAR(64) NOT NULL,
  entry_path VARCHAR(4096) NOT NULL,
  name VARCHAR(255) NOT NULL,
  directory BOOLEAN NOT NULL,
  byte_size BIGINT NOT NULL,
  modified_at BIGINT NOT NULL,
  modified_submillis INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT sc_file_catalog_path_uq UNIQUE(actor_id,mount_id,scope_hash,parent_hash,path_hash)
);
CREATE INDEX sc_file_catalog_page_idx ON sc_file_catalog_entry(actor_id,mount_id,scope_hash,parent_hash,directory,name);

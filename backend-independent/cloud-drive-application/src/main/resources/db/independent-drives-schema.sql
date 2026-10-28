CREATE TABLE sc_content_settings (id INTEGER NOT NULL PRIMARY KEY);
INSERT INTO sc_content_settings(id) VALUES(1);

CREATE TABLE sc_drive_mount (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  mount_key VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  workspace_id BIGINT,
  owner_id BIGINT NOT NULL,
  engine VARCHAR(20) NOT NULL,
  physical_root VARCHAR(2048) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL
);
CREATE INDEX sc_drive_mount_workspace_idx ON sc_drive_mount(workspace_id);

CREATE TABLE sc_drive_grant (
  user_id BIGINT NOT NULL,
  mount_id BIGINT NOT NULL,
  root_path VARCHAR(1024) NOT NULL,
  permissions VARCHAR(1024) NOT NULL,
  quota_bytes BIGINT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (user_id, mount_id),
  CONSTRAINT sc_drive_grant_mount_fk FOREIGN KEY (mount_id) REFERENCES sc_drive_mount(id)
);

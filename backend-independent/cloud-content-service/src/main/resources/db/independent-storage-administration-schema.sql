-- Editor-owned metadata for independent drive mounts. Physical roots and credentials are not duplicated here.
CREATE TABLE sc_storage_admin_metadata (
  mount_id BIGINT NOT NULL PRIMARY KEY,
  order_num INTEGER NOT NULL DEFAULT 0,
  remark VARCHAR(1000),
  default_switch_to_img_mode BOOLEAN NOT NULL DEFAULT FALSE,
  enable_file_operator BOOLEAN NOT NULL DEFAULT TRUE,
  enable_file_anno_operator BOOLEAN NOT NULL DEFAULT FALSE,
  enable_cache BOOLEAN NOT NULL DEFAULT FALSE,
  auto_refresh_cache BOOLEAN NOT NULL DEFAULT FALSE,
  cache_revision BIGINT NOT NULL DEFAULT 1,
  CONSTRAINT sc_storage_admin_mount_fk FOREIGN KEY (mount_id) REFERENCES sc_drive_mount(id)
);

-- Each member owns a separate saved permission preset; editors are authorized through the platform port.
-- Saving a preset alone does not automatically grant access or change future cloud approvals.
CREATE TABLE sc_workspace_member_permission_defaults (
  user_id BIGINT NOT NULL PRIMARY KEY,
  workspace_id BIGINT NOT NULL,
  permissions VARCHAR(1024) NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE TABLE sc_cloud_access_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  requester_id BIGINT NOT NULL,
  mount_id BIGINT NOT NULL,
  workspace_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  pending_key VARCHAR(100) UNIQUE,
  decided_by BIGINT,
  reason VARCHAR(500),
  created_at BIGINT NOT NULL,
  decided_at BIGINT,
  CONSTRAINT sc_cloud_access_request_mount_fk FOREIGN KEY (mount_id) REFERENCES sc_drive_mount(id)
);
CREATE INDEX sc_cloud_access_request_workspace_idx ON sc_cloud_access_request(workspace_id,status,id);
CREATE INDEX sc_cloud_access_request_user_idx ON sc_cloud_access_request(requester_id,id);

CREATE TABLE sc_local_storage_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  requester_id BIGINT NOT NULL,
  workspace_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  status VARCHAR(16) NOT NULL,
  pending_key VARCHAR(100) UNIQUE,
  mount_id BIGINT,
  decided_by BIGINT,
  reason VARCHAR(500),
  created_at BIGINT NOT NULL,
  decided_at BIGINT,
  CONSTRAINT sc_local_storage_request_mount_fk FOREIGN KEY (mount_id) REFERENCES sc_drive_mount(id)
);
CREATE INDEX sc_local_storage_request_state_idx ON sc_local_storage_request(status,id);
CREATE INDEX sc_local_storage_request_user_idx ON sc_local_storage_request(requester_id,id);

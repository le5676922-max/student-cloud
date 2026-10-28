-- Platform-owned enrollment and invitation state. These references stay within the platform database.
CREATE TABLE sc_workspace_enrollment_guard (
  id INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO sc_workspace_enrollment_guard(id) VALUES(1);

CREATE TABLE sc_workspace_invitation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id BIGINT NOT NULL,
  creator_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL UNIQUE,
  expires_at BIGINT,
  max_uses INTEGER,
  use_count INTEGER NOT NULL DEFAULT 0,
  revoked_at BIGINT,
  created_at BIGINT NOT NULL,
  CONSTRAINT sc_workspace_invitation_workspace_fk FOREIGN KEY(workspace_id) REFERENCES sc_workspace(id),
  CONSTRAINT sc_workspace_invitation_creator_fk FOREIGN KEY(creator_id) REFERENCES sc_account(id)
);
CREATE INDEX sc_workspace_invitation_owner_idx ON sc_workspace_invitation(workspace_id,id);

CREATE TABLE sc_workspace_join_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL,
  reviewed_by BIGINT,
  created_at BIGINT NOT NULL,
  reviewed_at BIGINT,
  CONSTRAINT sc_workspace_join_workspace_fk FOREIGN KEY(workspace_id) REFERENCES sc_workspace(id),
  CONSTRAINT sc_workspace_join_account_fk FOREIGN KEY(account_id) REFERENCES sc_account(id),
  CONSTRAINT sc_workspace_join_reviewer_fk FOREIGN KEY(reviewed_by) REFERENCES sc_account(id)
);
CREATE INDEX sc_workspace_join_state_idx ON sc_workspace_join_request(workspace_id,status,id);

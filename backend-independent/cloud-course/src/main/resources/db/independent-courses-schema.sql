CREATE TABLE sc_content_owner_guard (account_id BIGINT NOT NULL PRIMARY KEY);

CREATE TABLE sc_shared_course (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  quota_bytes BIGINT NOT NULL,
  used_bytes BIGINT NOT NULL DEFAULT 0,
  material_count BIGINT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  deleted_at BIGINT
);
CREATE INDEX sc_shared_course_owner_idx ON sc_shared_course(owner_id,deleted_at);

CREATE TABLE sc_course_member (
  course_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  upload_status VARCHAR(16) NOT NULL,
  joined_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY(course_id,account_id),
  CONSTRAINT sc_course_member_course_fk FOREIGN KEY(course_id) REFERENCES sc_shared_course(id)
);
CREATE INDEX sc_course_member_account_idx ON sc_course_member(account_id,state);

CREATE TABLE sc_course_invitation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  course_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL UNIQUE,
  expires_at BIGINT,
  max_members INTEGER,
  used_count BIGINT NOT NULL DEFAULT 0,
  revoked_at BIGINT,
  created_at BIGINT NOT NULL,
  CONSTRAINT sc_course_invitation_course_fk FOREIGN KEY(course_id) REFERENCES sc_shared_course(id)
);
CREATE INDEX sc_course_invitation_course_idx ON sc_course_invitation(course_id,id);

CREATE TABLE sc_course_invitation_use (
  invitation_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  first_used_at BIGINT NOT NULL,
  PRIMARY KEY(invitation_id,account_id),
  CONSTRAINT sc_course_invitation_use_invite_fk FOREIGN KEY(invitation_id) REFERENCES sc_course_invitation(id)
);

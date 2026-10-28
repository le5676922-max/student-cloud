CREATE TABLE sc_workspace (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at BIGINT NOT NULL
);

CREATE TABLE sc_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(40) NOT NULL UNIQUE,
  username VARCHAR(64) NOT NULL UNIQUE,
  email VARCHAR(254) UNIQUE,
  password_hash VARCHAR(256) NOT NULL,
  role VARCHAR(20) NOT NULL,
  workspace_id BIGINT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  session_version BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  CONSTRAINT sc_account_workspace_fk FOREIGN KEY (workspace_id) REFERENCES sc_workspace(id)
);

CREATE TABLE sc_session (
  token_hash CHAR(64) NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  session_version BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT sc_session_account_fk FOREIGN KEY (account_id) REFERENCES sc_account(id)
);
CREATE INDEX sc_session_account_idx ON sc_session(account_id);
CREATE INDEX sc_session_expiry_idx ON sc_session(expires_at);

CREATE TABLE sc_platform_settings (
  id INTEGER NOT NULL PRIMARY KEY,
  site_name VARCHAR(120) NOT NULL,
  installed BOOLEAN NOT NULL DEFAULT FALSE,
  installed_at BIGINT
);
INSERT INTO sc_platform_settings(id, site_name, installed) VALUES(1, 'Student Cloud', FALSE);

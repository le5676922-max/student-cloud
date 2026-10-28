-- Platform-owned email challenges. No clear-text codes or SMTP credentials are stored here.
CREATE TABLE sc_email_budget (
  id INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO sc_email_budget(id) VALUES(1);

CREATE TABLE sc_email_challenge (
  email VARCHAR(254) NOT NULL,
  purpose VARCHAR(24) NOT NULL,
  code_hash CHAR(64),
  generation CHAR(32),
  status VARCHAR(16) NOT NULL,
  expires_at BIGINT NOT NULL,
  attempts INTEGER NOT NULL,
  next_send_at BIGINT NOT NULL,
  window_started_at BIGINT NOT NULL,
  sent_count INTEGER NOT NULL,
  PRIMARY KEY(email,purpose)
);
CREATE INDEX sc_email_challenge_cleanup_idx ON sc_email_challenge(expires_at,email,purpose);

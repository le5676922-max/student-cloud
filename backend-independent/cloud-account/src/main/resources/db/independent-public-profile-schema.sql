-- Additive platform-owned registration profile; no email codes or credentials are retained here.
CREATE TABLE sc_public_profile (
  account_id BIGINT NOT NULL PRIMARY KEY,
  nickname VARCHAR(100) NOT NULL,
  email_verified_at BIGINT NOT NULL,
  CONSTRAINT sc_public_profile_account_fk FOREIGN KEY(account_id) REFERENCES sc_account(id)
);

-- Manual member creation must remain unique even for simultaneous requests.
-- Existing duplicates must be resolved explicitly; this migration never deletes/renames users.
CREATE UNIQUE INDEX uk_user_username ON user (username);

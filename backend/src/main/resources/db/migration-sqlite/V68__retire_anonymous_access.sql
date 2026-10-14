-- Preserve historical accounts, files and grant rows without turning retired records into login accounts.
UPDATE `user` SET enable = 0 WHERE id = 2 OR role = 'GUEST';
UPDATE user_storage_source SET enable = 0
WHERE user_id IN (SELECT id FROM `user` WHERE id = 2 OR role = 'GUEST');
UPDATE system_config SET value = 'false'
WHERE name IN ('webdavAllowAnonymous', 'allowPathLinkAnonAccess');
-- The saved homepage is historical data; it is no longer exposed or rendered.

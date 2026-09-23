-- Keep existing accounts, storage grants and files. Only simplify the role model.
UPDATE `user` SET role = CASE WHEN id = 1 THEN 'ADMIN' WHEN id = 2 THEN 'GUEST' ELSE 'USER' END;
UPDATE `user` SET tenant_id = NULL WHERE id = 1;
UPDATE `user` SET password = NULL, salt = NULL WHERE id IN (0, 2);
UPDATE `user` SET nickname = '访客' WHERE id = 2;
UPDATE `user` SET nickname = '新用户权限模板' WHERE id = 0;
-- Workspaces are now managed only by the system administrator.
UPDATE tenant SET owner_user_id = 1 WHERE owner_user_id IS NOT NULL;

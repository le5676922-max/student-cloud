CREATE TABLE tenant
(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    owner_user_id INT          NULL,
    enable        BIT          NOT NULL DEFAULT 1,
    create_time   DATETIME     NULL,
    update_time   DATETIME     NULL,
    CONSTRAINT uk_tenant_name UNIQUE (name)
) COMMENT '管理员工作空间';

INSERT INTO tenant (id, name, owner_user_id, enable, create_time)
VALUES (1, '默认工作空间', 1, 1, CURRENT_TIMESTAMP);

ALTER TABLE user
    ADD COLUMN tenant_id INT NULL COMMENT '所属工作空间',
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT '用户角色';

UPDATE user SET tenant_id = 1, role = 'USER';
UPDATE user SET tenant_id = NULL, role = 'SUPER_ADMIN' WHERE id = 1;

ALTER TABLE storage_source
    ADD COLUMN tenant_id INT NOT NULL DEFAULT 1 COMMENT '所属工作空间',
    ADD COLUMN owner_user_id INT NULL COMMENT '创建管理员';

UPDATE storage_source SET tenant_id = 1, owner_user_id = 1;


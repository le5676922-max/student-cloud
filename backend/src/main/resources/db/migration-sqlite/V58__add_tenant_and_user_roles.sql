CREATE TABLE tenant
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          VARCHAR(100) NOT NULL UNIQUE,
    owner_user_id INTEGER      NULL,
    enable        BIT          NOT NULL DEFAULT 1,
    create_time   DATETIME     NULL,
    update_time   DATETIME     NULL
);

INSERT INTO tenant (id, name, owner_user_id, enable, create_time)
VALUES (1, '默认工作空间', 1, 1, datetime(CURRENT_TIMESTAMP, 'localtime'));

ALTER TABLE user ADD COLUMN tenant_id INTEGER NULL;
ALTER TABLE user ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER';

UPDATE user SET tenant_id = 1, role = 'USER';
UPDATE user SET tenant_id = NULL, role = 'SUPER_ADMIN' WHERE id = 1;

ALTER TABLE storage_source ADD COLUMN tenant_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE storage_source ADD COLUMN owner_user_id INTEGER NULL;

UPDATE storage_source SET tenant_id = 1, owner_user_id = 1;


-- 通理工学生云盘：MySQL 数据库重建脚本
--
-- 警告：执行本脚本会永久删除 student_cloud 数据库中的全部数据，
-- 包括用户、存储源配置、分享、日志和系统设置。
-- 云盘实际文件通常位于磁盘或第三方存储，不在数据库中，但仍应先完整备份。
--
-- 本脚本适用于 MySQL 8.x。执行完成后不要手工导入 migration-mysql 目录中的脚本；
-- 正常启动后端，Flyway 会按照 V1 至 V59 自动创建当前版本的表结构和初始化数据。

SET NAMES utf8mb4;
SET time_zone = '+08:00';

DROP DATABASE IF EXISTS `student_cloud`;

CREATE DATABASE `student_cloud`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `student_cloud`;

-- 数据库现在应为空。下面的查询用于确认当前数据库与字符集。
SELECT
    DATABASE() AS `database_name`,
    DEFAULT_CHARACTER_SET_NAME AS `character_set`,
    DEFAULT_COLLATION_NAME AS `collation`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'student_cloud';

-- 下一步：退出 MySQL，然后用默认配置启动后端。
-- Flyway 会自动执行：
--   zfile-main/src/main/resources/db/migration-mysql/V*.sql
--   zfile-main/src/main/resources/db/migration-mysql/R__data.sql
-- 启动完成后访问网页，重新初始化站点管理员。

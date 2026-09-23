-- Tongligong Student Cloud - MySQL database bootstrap
-- WARNING: this permanently removes all data from the student_cloud database.
-- Back up the database before executing this file.
--
-- The application owns the schema through Flyway. This script recreates only
-- the empty database. On the next normal backend start, Flyway applies every
-- migration under db/migration-mysql and creates the complete current schema.

SET NAMES utf8mb4;
SET time_zone = '+08:00';

DROP DATABASE IF EXISTS `student_cloud`;

CREATE DATABASE `student_cloud`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `student_cloud`;

SELECT
    DATABASE() AS `database_name`,
    DEFAULT_CHARACTER_SET_NAME AS `character_set`,
    DEFAULT_COLLATION_NAME AS `collation`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'student_cloud';

package im.tlgxy.cloud.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class RoleMigrationTest {
    @TempDir Path directory;

    @Test
    void retiresAnonymousAccountsAndGrantsWithoutDeletingOrPromotingData() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("anonymous-retirement.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite")
                .target("67").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO user (id, username, nickname, enable, role, tenant_id, password, default_permissions) "
                    + "VALUES (10, 'historical-account', 'preserved', 1, 'GUEST', 1, 'preserved-password', 'preview,download')");
            sql.executeUpdate("INSERT INTO user (id, username, enable, role, tenant_id) VALUES (11, 'member', 1, 'USER', 1)");
            sql.executeUpdate("INSERT INTO user_storage_source (user_id, storage_source_id, root_path, enable, permissions) "
                    + "VALUES (10, 123, '/preserved/', 1, 'preview,download'), (2, 123, '/', 1, 'preview'), (11, 123, '/', 1, 'download')");
            sql.executeUpdate("INSERT INTO learning_course(owner_user_id, name, semester) VALUES(10, 'preserved course', '2026')");
            sql.executeUpdate("UPDATE system_config SET value = 'true' WHERE name IN ('webdavAllowAnonymous', 'allowPathLinkAnonAccess')");
            sql.executeUpdate("UPDATE system_config SET value = 'preserved homepage' WHERE name = 'guestIndexHtml'");
        }
        Flyway flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite").target("68").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT id, role, enable, password, default_permissions FROM user ORDER BY id")) {
                int count = 0;
                while (rows.next()) {
                    int id = rows.getInt("id");
                    assertEquals(id == 1 ? "ADMIN" : id == 2 || id == 10 ? "GUEST" : "USER", rows.getString("role"));
                    assertEquals(id != 2 && id != 10, rows.getBoolean("enable"));
                    if (id == 10) {
                        assertEquals("preserved-password", rows.getString("password"));
                        assertEquals("preview,download", rows.getString("default_permissions"));
                    }
                    count++;
                }
                assertEquals(5, count);
            }
            try (var rows = sql.executeQuery("SELECT user_id, root_path, enable, permissions FROM user_storage_source WHERE storage_source_id = 123 ORDER BY user_id")) {
                int count = 0;
                while (rows.next()) {
                    int userId = rows.getInt("user_id");
                    assertEquals(userId == 11, rows.getBoolean("enable"));
                    if (userId == 10) {
                        assertEquals("/preserved/", rows.getString("root_path"));
                        assertEquals("preview,download", rows.getString("permissions"));
                    }
                    count++;
                }
                assertEquals(3, count);
            }
            try (var rows = sql.executeQuery("SELECT name FROM learning_course WHERE owner_user_id = 10")) {
                assertTrue(rows.next());
                assertEquals("preserved course", rows.getString(1));
            }
            try (var rows = sql.executeQuery("SELECT name, value FROM system_config WHERE name IN ('webdavAllowAnonymous', 'allowPathLinkAnonAccess', 'guestIndexHtml')")) {
                int count = 0;
                while (rows.next()) {
                    assertEquals(rows.getString("name").equals("guestIndexHtml") ? "preserved homepage" : "false", rows.getString("value"));
                    count++;
                }
                assertEquals(3, count);
            }
        }
    }

    @Test
    void redactsHistoricalPasswordsWithoutDeletingLoginEvents() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("password-redaction.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite")
                .target("63").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO login_log(username, password, result) VALUES('test', 'old-secret', '登录失败')");
            sql.executeUpdate("UPDATE system_config SET value = 'all' WHERE name = 'loginLogMode'");
        }
        Flyway flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite").target("64").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT username, password, result FROM login_log WHERE username = 'test'")) {
                assertTrue(rows.next());
                assertEquals("******", rows.getString("password"));
                assertEquals("登录失败", rows.getString("result"));
                assertFalse(rows.next());
            }
            try (var rows = sql.executeQuery("SELECT value FROM system_config WHERE name = 'loginLogMode'")) {
                assertTrue(rows.next());
                assertEquals("ignoreAllPwd", rows.getString(1));
            }
        }
    }

    @Test
    void taskMigrationPreservesExistingV61CoursesAndCanBeAppliedOnlyOnce() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("agent-upgrade.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite")
                .target("61").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO learning_course(owner_user_id, name, semester) VALUES(1, 'preserved course', '2026')");
        }
        Flyway flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite").target("64").load();
        assertEquals(3, flyway.migrate().migrationsExecuted); // V62 tasks + V63 schedules + V64 log redaction
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT name FROM learning_course")) { assertTrue(rows.next()); assertEquals("preserved course", rows.getString(1)); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM ai_task")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM ai_schedule")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }

    @Test
    void learningMigrationUpgradesV60WithoutChangingExistingAccounts() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("learning-upgrade.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite")
                .target("60").load().migrate();
        int accounts;
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT count(*) FROM user")) {
            assertTrue(rows.next());
            accounts = rows.getInt(1);
        }
        Flyway flyway = Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite").target("64").load();
        assertEquals(4, flyway.migrate().migrationsExecuted); // V61 learning + V62 tasks + V63 schedules + V64 redaction
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT count(*) FROM user")) { assertTrue(rows.next()); assertEquals(accounts, rows.getInt(1)); }
            sql.executeUpdate("INSERT INTO learning_course(owner_user_id, name, semester) VALUES(1, 'test', '2026')");
            try (var rows = sql.executeQuery("SELECT count(*) FROM learning_material")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }

    @Test
    void upgradesLegacyRolesWithoutDeletingAccountsOrFileGrants() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("legacy.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration-sqlite")
                .target("59").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO user (id, username, nickname, enable, role, tenant_id, password, default_permissions) "
                    + "VALUES (10, 'legacy-owner', 'Owner', 1, 'TENANT_ADMIN', 1, 'unchanged-password', 'preview,download')");
            sql.executeUpdate("INSERT INTO user_storage_source (user_id, storage_source_id, root_path, enable, permissions) "
                    + "VALUES (10, 123, '/private/', 1, 'preview,download')");
            sql.executeUpdate("UPDATE tenant SET owner_user_id = 10 WHERE id = 1");
            sql.executeUpdate("UPDATE user SET password = 'legacy-secret', salt = 'legacy-salt' WHERE id IN (0, 2)");
        }
        Flyway flyway = Flyway.configure().dataSource(url, "", "")
                .locations("classpath:db/migration-sqlite").target("60").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT id, role, password, salt FROM user ORDER BY id")) {
                int count = 0;
                while (rows.next()) {
                    int id = rows.getInt("id");
                    assertEquals(id == 1 ? "ADMIN" : id == 2 ? "GUEST" : "USER", rows.getString("role"));
                    if (id == 0 || id == 2) {
                        assertNull(rows.getString("password"));
                        assertNull(rows.getString("salt"));
                    }
                    if (id == 10) assertEquals("unchanged-password", rows.getString("password"));
                    count++;
                }
                assertEquals(4, count);
            }
            try (var rows = sql.executeQuery("SELECT root_path, permissions, enable FROM user_storage_source WHERE user_id = 10")) {
                assertTrue(rows.next());
                assertEquals("/private/", rows.getString("root_path"));
                assertEquals("preview,download", rows.getString("permissions"));
                assertTrue(rows.getBoolean("enable"));
            }
            try (var rows = sql.executeQuery("SELECT owner_user_id FROM tenant WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}

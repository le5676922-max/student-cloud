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
                .locations("classpath:db/migration-sqlite").load();
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

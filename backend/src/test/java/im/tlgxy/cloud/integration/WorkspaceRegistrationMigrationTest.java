package im.tlgxy.cloud.integration;

import im.tlgxy.cloud.module.tenant.mapper.WorkspaceInvitationMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRegistrationMigrationTest {
    @Test void sqliteMigrationPreservesExistingLoginsAndDatabaseStopsInviteAndApprovalReplay() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE user (id INTEGER PRIMARY KEY, username TEXT, enable BOOLEAN, role TEXT, tenant_id INTEGER)");
            connection.createStatement().execute("INSERT INTO user VALUES (1, 'legacy-admin', 1, 'ADMIN', NULL), (2, 'guest', 1, 'GUEST', 1)");
            connection.createStatement().execute("CREATE TABLE tenant (id INTEGER PRIMARY KEY, enable BOOLEAN)");
            connection.createStatement().execute("INSERT INTO tenant VALUES (10,1), (20,0)");
            try (var resource = getClass().getResourceAsStream("/db/migration-sqlite/V65__public_registration_and_workspace_join.sql")) {
                assertNotNull(resource);
                for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) connection.createStatement().execute(sql);
            }
            try (var values = connection.createStatement().executeQuery("SELECT username, account_id FROM user WHERE id=1")) {
                assertTrue(values.next()); assertEquals("legacy-admin", values.getString(1)); assertEquals("SC000000000001", values.getString(2));
            }
            connection.createStatement().execute("UPDATE user SET email='verified@example.com' WHERE id=1");
            assertThrows(java.sql.SQLException.class, () -> connection.createStatement().execute("UPDATE user SET email='verified@example.com' WHERE id=2"));
            connection.createStatement().execute("INSERT INTO workspace_invitation (id,tenant_id,issuer_user_id,token_hash,created_at,expires_at,max_uses,use_count,revoked) VALUES (7,10,1,'hash',1,1000,1,0,0)");
            String consume = WorkspaceInvitationMapper.class.getMethod("consume", int.class, int.class, long.class).getAnnotation(Update.class).value()[0];
            assertEquals(0, update(connection, consume, 7, 20, 500));
            assertEquals(0, update(connection, consume, 7, 10, 1000));
            assertEquals(1, update(connection, consume, 7, 10, 500));
            assertEquals(0, update(connection, consume, 7, 10, 500));
            connection.createStatement().execute("UPDATE workspace_invitation SET use_count=0, revoked=1 WHERE id=7");
            assertEquals(0, update(connection, consume, 7, 10, 500));
            connection.createStatement().execute("INSERT INTO workspace_join_request (id,tenant_id,user_id,status,created_at) VALUES (1,10,2,'PENDING',1)");
            String review = WorkspaceJoinRequestMapper.class.getMethod("reviewPending", int.class, int.class, String.class, long.class, int.class).getAnnotation(Update.class).value()[0];
            assertEquals(0, update(connection, review, "APPROVED", 500, 1, 1, 20));
            assertEquals(1, update(connection, review, "APPROVED", 500, 1, 1, 10));
            assertEquals(0, update(connection, review, "REJECTED", 600, 1, 1, 10));
            String tenantLock = WorkspaceInvitationMapper.class.getMethod("lockEnabledTenant", int.class).getAnnotation(Update.class).value()[0];
            assertEquals(1, update(connection, tenantLock, 10));
            assertEquals(0, update(connection, tenantLock, 20));
        }
    }

    private int update(Connection connection, String annotatedSql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(annotatedSql.replaceAll("#\\{[^}]+}", "?"))) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            return statement.executeUpdate();
        }
    }
}

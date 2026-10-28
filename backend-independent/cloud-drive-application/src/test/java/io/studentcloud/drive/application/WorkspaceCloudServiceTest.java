package io.studentcloud.drive.application;

import io.studentcloud.account.Actor;
import io.studentcloud.account.JdbcAccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCloudServiceTest {
    @TempDir Path temporary;
    private JdbcTemplate jdbc;
    private MountService mounts;
    private WorkspaceCloudService workspace;
    private Path managed;
    private final Actor global = new Actor(1, "SC1", "global", null, Role.ADMIN, null);
    private final Actor tenant = new Actor(2, "SC2", "tenant", null, Role.TENANT_ADMIN, 1L);

    @BeforeEach void initialize() throws IOException {
        managed = Files.createDirectory(temporary.resolve("managed"));
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:approvals_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),
                new ClassPathResource("db/independent-drives-schema.sql"),
                new ClassPathResource("db/independent-cloud-access-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        var tx = new DataSourceTransactionManager(database);
        mounts = new MountService(jdbc, tx, Clock.systemUTC(), managed, new JdbcAccountDirectory(jdbc));
        workspace = new WorkspaceCloudService(jdbc, tx, Clock.systemUTC(), managed, new JdbcAccountDirectory(jdbc));
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'one',TRUE,1)");
        for (Actor actor : java.util.List.of(global, tenant)) {
            jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,'unused',?,?,TRUE,1,1)",
                    actor.id(), actor.accountId(), actor.username(), actor.role().name(), actor.workspaceId());
        }
    }

    @Test void localApprovalUsesDedicatedRootAndOnlyTheOwnersPersonalDirectory() {
        var request = workspace.requestLocal(tenant, "资料云盘");
        var approved = workspace.approveLocal(global, request.id());
        assertEquals("APPROVED", approved.status());
        assertNotNull(approved.mountId());
        String root = jdbc.queryForObject("SELECT physical_root FROM sc_drive_mount WHERE id=?", String.class, approved.mountId());
        assertNotEquals(managed, Path.of(root));
        assertTrue(Path.of(root).startsWith(managed.resolve("space-1")));
        assertTrue(Files.isDirectory(Path.of(root).resolve("admins/u2")));
        assertEquals("/admins/u2", jdbc.queryForObject("SELECT root_path FROM sc_drive_grant WHERE user_id=2 AND mount_id=?", String.class, approved.mountId()));
        assertEquals(1, mounts.listMounts(tenant).size());
    }
    @Test void cloudApplicationKeepsTheMembersExplanationUntilReview(){
        Actor member=new Actor(3,"SC3","member",null,Role.USER,1L);
        jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(3,'SC3','member','unused','USER',1,TRUE,1,1)");
        var approved=workspace.approveLocal(global,workspace.requestLocal(tenant,"课程云盘").id());
        var request=workspace.requestCloud(member,approved.mountId(),"保存课程作业");
        assertEquals("保存课程作业",request.reason());
        assertEquals("保存课程作业",workspace.pendingForWorkspaceAdmin(tenant).getFirst().reason());
        assertEquals(request.id(),workspace.requestCloud(member,approved.mountId(),"重复点击").id());
        assertEquals("保存课程作业",workspace.listMyRequests(member).getFirst().reason());
    }

    @Test void approvalCannotAllocateBelowAnExistingMountAndCompensatesOnlyItsOwnEmptyDirectories() throws IOException {
        Path space = Files.createDirectory(managed.resolve("space-1"));
        Files.writeString(space.resolve("keep"), "existing data");
        mounts.createLocal(global, "existing", "existing", space);
        var request = workspace.requestLocal(tenant, "overlap");
        ApplicationFault failure = assertThrows(ApplicationFault.class, () -> workspace.approveLocal(global, request.id()));
        assertEquals(409, failure.httpStatus());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM sc_local_storage_request WHERE id=?", String.class, request.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount", Integer.class));
        assertEquals("existing data", Files.readString(space.resolve("keep")));
        try (var children = Files.list(space)) { assertEquals(1, children.count()); }
    }
}

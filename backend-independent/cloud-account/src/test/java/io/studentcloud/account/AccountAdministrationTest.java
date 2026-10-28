package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AccountAdministrationTest {
    private static final String PASSWORD = "Fixture-password-123";
    private JdbcTemplate jdbc;
    private AccountService accounts;
    private AccountAdministration administration;
    private Actor global;

    @BeforeEach
    void independentIdentityDatabase() {
        var database = new DriverManagerDataSource("jdbc:h2:mem:management_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        var manager = new DataSourceTransactionManager(database);
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
        accounts = new AccountService(jdbc, new TransactionTemplate(manager), clock, Duration.ofHours(1));
        administration = new AccountAdministration(jdbc, manager, clock);
        accounts.install("Isolated fixture", "root-fixture", PASSWORD);
        global = accounts.authenticate(accounts.login("root-fixture", PASSWORD).token());
    }

    @Test
    void createsAdministratorAndWorkspaceAtomicallyThenCreatesOnlyLocalMembers() {
        var owner = administration.createWorkspaceAdministrator(global, " Owner-One ", " Owner@One.Example ", PASSWORD, "课程空间");
        assertEquals(Role.TENANT_ADMIN, owner.role());
        assertNotNull(owner.workspaceId());
        assertEquals("owner-one", owner.username());
        assertEquals("owner@one.example", owner.email());
        assertTrue(owner.accountId().matches("SC[0-9A-F]{32}"));
        Actor actor = actor(owner);
        var member = administration.createMember(actor, " Student-One ", " Student@One.Example ", PASSWORD);
        assertEquals(Role.USER, member.role());
        assertEquals(owner.workspaceId(), member.workspaceId());
        assertEquals(1, administration.listWorkspaceMembers(actor, 0, 20, "student").size());
        assertEquals(1, administration.countWorkspaceMembers(actor, "student"));
        assertEquals(0, administration.countWorkspaceMembers(actor, "missing"));
        assertEquals(1, administration.listWorkspaceAdministrators(global, 0, 20).size());
        assertEquals(1, administration.countWorkspaceAdministrators(global));
        assertEquals(member.id(), accounts.login(member.accountId(), PASSWORD).account().id());
    }

    @Test
    void globalCannotManageMembersAndDifferentSpacesAreNeverReturnedOrModified() {
        Actor first = owner("first");
        Actor second = owner("second");
        var member = administration.createMember(first, "local-member", "local@one.example", PASSWORD);
        assertTrue(administration.listWorkspaceMembers(second, 0, 20, "").isEmpty());
        assertEquals(0, administration.countWorkspaceMembers(second, ""));
        assertEquals(404, assertThrows(ApplicationFault.class,
                () -> administration.setMemberEnabled(second, member.id(), false)).httpStatus());
        assertEquals(404, assertThrows(ApplicationFault.class,
                () -> administration.resetMemberPassword(second, member.id(), "Replacement-123")).httpStatus());
        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> administration.listWorkspaceMembers(global, 0, 20, "")).httpStatus());
        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> administration.createMember(global, "bad-member", "bad@example.com", PASSWORD)).httpStatus());
        assertEquals(404, assertThrows(ApplicationFault.class,
                () -> administration.setMemberEnabled(first, first.id(), false)).httpStatus());
        assertTrue(administration.listWorkspaceMembers(first, 0, 20, "").getFirst().enabled());
    }

    @Test
    void everyOperationRechecksCurrentCallerAndWorkspaceInsteadOfTrustingActorRole() {
        Actor owner = owner("fresh");
        var fakeGlobal = new Actor(owner.id(), owner.accountId(), owner.username(), owner.email(), Role.ADMIN, null);
        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> administration.listWorkspaceAdministrators(fakeGlobal, 0, 20)).httpStatus());
        jdbc.update("UPDATE sc_workspace SET enabled=FALSE WHERE id=?", owner.workspaceId());
        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> administration.createMember(owner, "blocked-member", "blocked@example.com", PASSWORD)).httpStatus());
        jdbc.update("UPDATE sc_workspace SET enabled=TRUE WHERE id=?", owner.workspaceId());
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?", owner.id());
        assertEquals(401, assertThrows(ApplicationFault.class,
                () -> administration.listWorkspaceMembers(owner, 0, 20, "")).httpStatus());
        jdbc.update("UPDATE sc_account SET enabled=TRUE,role='USER' WHERE id=?", owner.id());
        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> administration.listWorkspaceMembers(owner, 0, 20, "")).httpStatus());
    }

    @Test
    void resetAndDisableRevokeExistingSessionsAndPasswordResetNeverTargetsAdministrator() {
        Actor owner = owner("password");
        var member = administration.createMember(owner, "password-member", "password-member@example.com", PASSWORD);
        var old = accounts.login(member.email(), PASSWORD);
        long version = jdbc.queryForObject("SELECT session_version FROM sc_account WHERE id=?", Long.class, member.id());
        administration.resetMemberPassword(owner, member.id(), "Replacement-password-456");
        assertEquals(version + 1, jdbc.queryForObject("SELECT session_version FROM sc_account WHERE id=?", Long.class, member.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_session WHERE account_id=?", Integer.class, member.id()));
        assertThrows(ApplicationFault.class, () -> accounts.authenticate(old.token()));
        assertThrows(ApplicationFault.class, () -> accounts.login(member.email(), PASSWORD));
        var fresh = accounts.login(member.email(), "Replacement-password-456");
        administration.setMemberEnabled(owner, member.id(), false);
        assertThrows(ApplicationFault.class, () -> accounts.authenticate(fresh.token()));
        administration.setMemberEnabled(owner, member.id(), true);
        assertThrows(ApplicationFault.class, () -> accounts.authenticate(fresh.token()));
        assertNotNull(accounts.login(member.accountId(), "Replacement-password-456").token());
        assertThrows(ApplicationFault.class, () -> administration.resetMemberPassword(owner, global.id(), "Cannot-reset-global-123"));
    }

    @Test
    void aliasesAreNormalizedUniqueAndCannotMasqueradeAsAssignedAccountIds() {
        Actor owner = owner("aliases");
        administration.createMember(owner, "first-alias", " User@Example.Com ", PASSWORD);
        int workspaces = jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace", Integer.class);
        assertEquals(409, assertThrows(ApplicationFault.class,
                () -> administration.createWorkspaceAdministrator(global, "new-owner", "user@example.com", PASSWORD, "must-not-survive")).httpStatus());
        assertEquals(workspaces, jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace", Integer.class));
        assertThrows(ApplicationFault.class, () -> administration.createMember(owner, "SCabc123", "reserved@example.com", PASSWORD));
        assertThrows(ApplicationFault.class, () -> administration.createMember(owner, "invalid-mail", "bad..local@example.com", PASSWORD));
        assertThrows(ApplicationFault.class, () -> administration.createMember(owner, "invalid-host", "user@-broken.example", PASSWORD));
        assertTrue(administration.listWorkspaceMembers(owner, 0, 20, "%").isEmpty());
        assertEquals(0, administration.countWorkspaceMembers(owner, "%"));
        assertThrows(ApplicationFault.class, () -> administration.listWorkspaceMembers(owner, 0, 101, ""));
    }

    private Actor owner(String name) {
        return actor(administration.createWorkspaceAdministrator(global, "owner-" + name, name + "@owner.example", PASSWORD, "Workspace " + name));
    }

    private Actor actor(AccountView view) {
        return accounts.authenticate(accounts.login(view.accountId(), PASSWORD).token());
    }
}

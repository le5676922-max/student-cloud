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

class PlatformAdministrationServiceTest {
    private static final String PASSWORD="Platform-test-password-123";
    private JdbcTemplate jdbc;
    private AccountService accounts;
    private PlatformAdministrationService admin;
    private Actor global;

    @BeforeEach void setup() {
        var database=new DriverManagerDataSource("jdbc:h2:mem:platform_admin_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-enrollment-schema.sql"),
                new ClassPathResource("db/independent-public-profile-schema.sql"),new ClassPathResource("db/independent-platform-administration-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);var tx=new DataSourceTransactionManager(database);var clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
        accounts=new AccountService(jdbc,new TransactionTemplate(tx),clock,Duration.ofHours(1));admin=new PlatformAdministrationService(jdbc,tx,clock);
        accounts.install("Platform fixture","global-fixture",PASSWORD);global=accounts.authenticate(accounts.login("global-fixture",PASSWORD).token());
    }

    @Test void globalDirectoryIsPagedSanitizedAndCountsOnlyPlatformOwnedData() {
        var workspace=admin.saveWorkspace(global,new PlatformAdministrationService.TenantInput(null,"Course Alpha",true));
        var ta=admin.createWorkspaceAdministrator(global,new PlatformAdministrationService.AdministratorInput("ta-alpha","ta@alpha.example",PASSWORD,"Alpha Admin",workspace.id(),"TENANT_ADMIN"));
        var member=admin.workspaceUsers(global,workspace.id(),"USER","",1,10);
        assertTrue(member.records().isEmpty());
        assertEquals(1,admin.workspaceUsers(global,workspace.id(),"TA","",1,10).total());
        assertEquals("Alpha Admin",admin.user(global,ta.id()).nickname());
        var page=admin.workspaces(global,"Alpha",true,null,1,10);
        assertEquals(1,page.total());assertEquals(ta.accountId(),page.records().getFirst().ownerAccountId());
        assertNull(page.records().getFirst().storageSources());
        var overview=admin.overview(global);
        assertEquals(1L,overview.get("workspaces"));assertEquals(0L,overview.get("members"));assertNull(overview.get("storageSources"));
        assertTrue(((java.util.Map<?,?>)overview.get("aggregateAvailability")).get("content").equals("unavailable"));
        assertFalse(jdbc.queryForList("SELECT password_hash FROM sc_account WHERE id=?",String.class,ta.id()).getFirst().equals(PASSWORD));
    }

    @Test void tenantCreateRenameAndDisableAreGlobalOnlyAndDisableBlocksAuthentication() {
        var workspace=admin.saveWorkspace(global,new PlatformAdministrationService.TenantInput(null,"Disable Me",true));
        var ta=admin.createWorkspaceAdministrator(global,new PlatformAdministrationService.AdministratorInput("ta-disable","ta-disable@example.org",PASSWORD,null,workspace.id(),"TENANT_ADMIN"));
        var login=accounts.login("ta-disable",PASSWORD);
        var renamed=admin.saveWorkspace(global,new PlatformAdministrationService.TenantInput(workspace.id(),"Renamed",true));
        assertEquals("Renamed",renamed.name());
        admin.saveWorkspace(global,new PlatformAdministrationService.TenantInput(workspace.id(),"Renamed",false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_session WHERE account_id=?",Integer.class,ta.id()));
        assertThrows(ApplicationFault.class,()->accounts.authenticate(login.token()));
        assertEquals(403,assertThrows(ApplicationFault.class,()->admin.saveWorkspace(taActor(ta),new PlatformAdministrationService.TenantInput(workspace.id(),"No",true))).httpStatus());
    }

    @Test void globalCannotMutateMemberPermissionsAndFreshGlobalRoleIsRequired() {
        var workspace=admin.saveWorkspace(global,new PlatformAdministrationService.TenantInput(null,"Scope",true));
        var ta=admin.createWorkspaceAdministrator(global,new PlatformAdministrationService.AdministratorInput("ta-scope","ta-scope@example.org",PASSWORD,null,workspace.id(),"TENANT_ADMIN"));
        jdbc.update("INSERT INTO sc_account(account_id,username,email,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES('SCMEMBER','member-one','member@scope.example','fake','USER',?,TRUE,1,?)",workspace.id(),1L);
        Long member=jdbc.queryForObject("SELECT id FROM sc_account WHERE username='member-one'",Long.class);
        Actor forged=new Actor(global.id(),global.accountId(),global.username(),global.email(),Role.TENANT_ADMIN,workspace.id());
        assertEquals(403,assertThrows(ApplicationFault.class,()->admin.workspaces(forged,"",null,null,1,10)).httpStatus());
        assertEquals(403,assertThrows(ApplicationFault.class,()->admin.createWorkspaceAdministrator(taActor(ta),new PlatformAdministrationService.AdministratorInput("ta-evil","evil@scope.example",PASSWORD,null,workspace.id(),"TENANT_ADMIN"))).httpStatus());
        assertEquals("USER",jdbc.queryForObject("SELECT role FROM sc_account WHERE username='member-one'",String.class));
        assertEquals(403,assertThrows(ApplicationFault.class,()->admin.user(taActor(ta),member)).httpStatus());
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?",global.id());
        assertEquals(401,assertThrows(ApplicationFault.class,()->admin.overview(global)).httpStatus());
    }

    @Test void settingsPersistOnlyAllowlistedKeysAndNeverStoreUnknownSecrets() {
        admin.updateConfiguration(global,java.util.Map.of("siteName","Renamed Site","siteHomeName","Home","smtpPassword","secret"),false);
        admin.updateConfiguration(global,java.util.Map.of("siteAdminVersionOpenChangeLog",true,"siteName","ignored"),true);
        var config=admin.configuration(global);
        assertEquals("Renamed Site",config.get("siteName"));assertEquals("Home",config.get("siteHomeName"));
        assertEquals(Boolean.TRUE,config.get("siteAdminVersionOpenChangeLog"));assertFalse(config.containsKey("smtpPassword"));
    }

    @Test void administratorEditsCannotChangeTenantRoleOrWorkspaceAndOwnPasswordNeedsOldProof() {
        var workspace=admin.saveWorkspace(global,new PlatformAdministrationService.TenantInput(null,"Edit Scope",true));
        var ta=admin.createWorkspaceAdministrator(global,new PlatformAdministrationService.AdministratorInput("ta-edit",null,PASSWORD,"Old Nickname",workspace.id(),"TENANT_ADMIN"));
        var changed=admin.saveAdministrator(global,new PlatformAdministrationService.AdministratorInput(ta.id(),"ta-edit-renamed",null,"",null,"New Nickname",null,"ADMIN",false));
        assertEquals("New Nickname",changed.nickname());assertEquals("TENANT_ADMIN",changed.role());assertEquals(workspace.id(),changed.tenantId());assertFalse(changed.enable());
        assertEquals("TENANT_ADMIN",jdbc.queryForObject("SELECT role FROM sc_account WHERE id=?",String.class,ta.id()));
        assertEquals(workspace.id(),jdbc.queryForObject("SELECT workspace_id FROM sc_account WHERE id=?",Long.class,ta.id()));
        assertEquals(400,assertThrows(ApplicationFault.class,()->admin.saveAdministrator(global,
                new PlatformAdministrationService.AdministratorInput(global.id(),"global-fixture",null,"Replacement-password-123",null,null,null,"ADMIN",true))).httpStatus());
        var self=admin.saveAdministrator(global,new PlatformAdministrationService.AdministratorInput(global.id(),"global-fixture",null,
                "Replacement-password-123",PASSWORD,null,null,"ADMIN",true));
        assertEquals("ADMIN",self.role());
        assertThrows(ApplicationFault.class,()->accounts.authenticate(accounts.login("global-fixture",PASSWORD).token()));
        assertNotNull(accounts.login("global-fixture","Replacement-password-123").token());
    }

    private Actor taActor(PlatformAdministrationService.Member member){return new Actor(member.id(),member.accountId(),member.username(),member.email(),Role.TENANT_ADMIN,member.tenantId());}
}

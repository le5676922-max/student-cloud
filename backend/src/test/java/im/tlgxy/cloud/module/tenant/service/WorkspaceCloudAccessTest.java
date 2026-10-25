package im.tlgxy.cloud.module.tenant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceCloudAccessTest {
    @TempDir Path temp;
    JdbcTemplate jdbc; WorkspaceCloudAccessService service; User actor; StorageSource source;
    WorkspaceMemberPermissionService permissions; TransactionTemplate tx;
    @BeforeEach void setup() {
        var data = new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("access.db")); data.setDriverClassName("org.sqlite.JDBC");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE user(id INTEGER PRIMARY KEY,role TEXT,nickname TEXT)");
        jdbc.execute("CREATE TABLE tenant(id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE storage_source(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.execute("CREATE TABLE user_storage_source(user_id INT,root_path TEXT,enable BOOLEAN)");
        jdbc.update("INSERT INTO user VALUES(10,'USER','member'),(12,'TENANT_ADMIN','manager')");
        jdbc.update("INSERT INTO tenant VALUES(9),(8)"); jdbc.update("INSERT INTO storage_source VALUES(31,'cloud')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V77__member_cloud_access_and_quota.sql")).execute(data);
        var security=mock(TenantSecurityService.class); var tenants=mock(TenantMapper.class); var sources=mock(StorageSourceMapper.class);
        var grants=mock(UserStorageSourceMapper.class); permissions=mock(WorkspaceMemberPermissionService.class);
        actor=new User(); actor.setId(10); actor.setRole(UserRoleEnum.USER); actor.setTenantId(9); actor.setEnable(true);
        when(security.currentUser()).thenAnswer(c->actor); when(security.currentManagedTenantId()).thenAnswer(c->{if(actor.getRole()!=UserRoleEnum.TENANT_ADMIN)throw new BizException("denied");return actor.getTenantId();});
        Tenant tenant=new Tenant(); tenant.setEnable(true); when(tenants.selectById(9)).thenReturn(tenant);
        source=new StorageSource();source.setId(31);source.setTenantId(9);source.setEnable(true);source.setType(StorageTypeEnum.S3);source.setName("cloud");
        when(sources.selectById(31)).thenReturn(source); when(sources.selectList(any())).thenReturn(List.of(source));
        var storage=new WorkspaceMemberPermissionService.StoragePermission(31,"cloud","/members/u10",false,Set.of(),"/members/u10",Set.of("preview","download"),5368709120L);
        when(permissions.get(10)).thenReturn(new WorkspaceMemberPermissionService.MemberPermissions(10,Set.of(),Set.of(),List.of(storage),List.of()));
        service=new WorkspaceCloudAccessService(security,tenants,sources,grants,permissions,jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(data));
    }
    @Test void repeatedApplicationIsIdempotentAndPublicProjectionContainsNoPeerIdentityOrCredentials() throws Exception {
        String id=service.apply(31,"study"),again=service.apply(31,"study"); assertEquals(id,again);
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_cloud_request",Long.class));
        String json=new ObjectMapper().writeValueAsString(service.available());
        assertTrue(json.contains("PENDING"));for(String forbidden:List.of("email","password","userId","nickname","credentials"))assertFalse(json.contains(forbidden));
    }
    @Test void foreignDisabledSourcesAndManagerApplicationsAreDenied() {
        source.setTenantId(8);assertThrows(BizException.class,()->service.apply(31,"x"));
        source.setTenantId(9);source.setEnable(false);assertThrows(BizException.class,()->service.apply(31,"x"));
        source.setEnable(true);actor.setRole(UserRoleEnum.TENANT_ADMIN);assertThrows(BizException.class,()->service.apply(31,"x"));
        assertThrows(BizException.class,()->service.available());
    }
    @Test void onlyCorrespondingManagerApprovesAndUsesThePersonalRootAndChosenQuota() {
        String id=service.apply(31,"study"); assertThrows(BizException.class,()->service.pending());
        actor.setId(12);actor.setRole(UserRoleEnum.TENANT_ADMIN);actor.setTenantId(8);
        assertThrows(BizException.class,()->service.review(id,new WorkspaceCloudAccessService.Review(true,10L,null,"")));
        actor.setTenantId(9);assertEquals(1,service.pending().size());
        tx.executeWithoutResult(s->service.review(id,new WorkspaceCloudAccessService.Review(true,123L,Set.of("preview"),"ok")));
        var captor=org.mockito.ArgumentCaptor.forClass(WorkspaceMemberPermissionService.UpdateRequest.class); verify(permissions).save(eq(10),captor.capture());
        assertEquals("/members/u10",captor.getValue().storages().getFirst().rootPath());assertEquals(123L,captor.getValue().storages().getFirst().quotaBytes());
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM workspace_cloud_request",String.class));
        assertThrows(BizException.class,()->service.review(id,new WorkspaceCloudAccessService.Review(true,99L,null,"")));
    }
    @Test void approvalFailureRollsBackDecisionAndRejectionCreatesNoGrant() {
        String id=service.apply(31,"study"); actor.setId(12);actor.setRole(UserRoleEnum.TENANT_ADMIN);
        doThrow(new BizException("权限已变化")).when(permissions).save(anyInt(),any());
        assertThrows(BizException.class,()->tx.executeWithoutResult(s->service.review(id,new WorkspaceCloudAccessService.Review(true,10L,null,""))));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM workspace_cloud_request",String.class));
        reset(permissions);tx.executeWithoutResult(s->service.review(id,new WorkspaceCloudAccessService.Review(false,null,null,"not needed")));
        verifyNoInteractions(permissions);assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM workspace_cloud_request",String.class));
    }
}

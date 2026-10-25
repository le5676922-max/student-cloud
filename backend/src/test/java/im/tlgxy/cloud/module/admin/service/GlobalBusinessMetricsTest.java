package im.tlgxy.cloud.module.admin.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalBusinessMetricsTest {
    @TempDir Path temp; JdbcTemplate jdbc; TenantSecurityService security; GlobalBusinessMetricsService service;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("metrics.db"));source.setDriverClassName("org.sqlite.JDBC");jdbc=new JdbcTemplate(source);
        for(String sql:new String[]{"CREATE TABLE tenant(id INT,name TEXT)","CREATE TABLE storage_source(type TEXT,tenant_id INT)",
                "CREATE TABLE storage_local_request(status TEXT,tenant_id INT)","CREATE TABLE workspace_cloud_request(status TEXT,tenant_id INT)",
                "CREATE TABLE ai_answer_feedback(status TEXT,tenant_id INT)","CREATE TABLE ai_curated_knowledge(feedback_id TEXT,version INT,state TEXT)",
                "CREATE TABLE ai_curated_knowledge_revision(feedback_id TEXT,version INT,scope TEXT,tenant_id INT,course_id TEXT)",
                "CREATE TABLE shared_course(id TEXT,tenant_id INT,enabled INT,used_bytes BIGINT,quota_bytes BIGINT)",
                "CREATE TABLE shared_course_member(course_id TEXT,status TEXT,upload_status TEXT)","CREATE TABLE shared_course_material(course_id TEXT,state TEXT)"})jdbc.execute(sql);
        jdbc.update("INSERT INTO tenant VALUES(9,'A'),(8,'B')");jdbc.update("INSERT INTO storage_source VALUES('local',9),('s3',8),('s3',8)");
        jdbc.update("INSERT INTO storage_local_request VALUES('PENDING',9),('APPROVED',8)");jdbc.update("INSERT INTO workspace_cloud_request VALUES('PENDING',8)");
        jdbc.update("INSERT INTO ai_answer_feedback VALUES('OPEN',9),('RESOLVED',8)");jdbc.update("INSERT INTO shared_course VALUES('a',9,1,12,100),('b',8,0,20,200)");
        jdbc.update("INSERT INTO shared_course_member VALUES('a','JOINED','PENDING'),('b','LEFT','PENDING')");jdbc.update("INSERT INTO shared_course_material VALUES('a','READY'),('b','PENDING')");
        jdbc.update("INSERT INTO ai_curated_knowledge VALUES('f',1,'PUBLISHED'),('g',1,'WITHDRAWN')");jdbc.update("INSERT INTO ai_curated_knowledge_revision VALUES('f',1,'COURSE',NULL,'a'),('g',1,'TENANT',8,NULL)");
        security=mock(TenantSecurityService.class);service=new GlobalBusinessMetricsService(jdbc,security);
    }
    @Test void aggregatesActualScopedRowsAndUnknownPersonalUsageIsNeverZero() {
        var all=service.get(null);assertEquals(2,all.storageTypes().size());assertEquals(32,all.courses().usedBytes());assertEquals(2,all.courses().total());assertEquals(1,all.courses().members());assertEquals(1,all.courses().materials());assertNull(all.personalStorageUsedBytes());
        var a=service.get(9);assertEquals(1,a.storageTypes().getFirst().count());assertEquals("local",a.storageTypes().getFirst().key());assertEquals(12,a.courses().usedBytes());assertEquals(1,a.knowledgeScopes().getFirst().count());assertEquals(1,a.courseUsageByWorkspace().size());assertEquals("A",a.courseUsageByWorkspace().getFirst().name());
        assertEquals(0,a.pendingApprovals().stream().filter(b->b.key().equals("MEMBER_CLOUD")).findFirst().orElseThrow().count());
    }
    @Test void cachedStatisticsRecheckGlobalAuthorityBeforeReturningAndInvalidFiltersDoNotRunQueries() {
        var first=service.get(null);jdbc.update("INSERT INTO storage_source VALUES('s3',8)");assertSame(first,service.get(null));verify(security,times(2)).requireSystemAdmin();
        doThrow(new BizException("role revoked")).when(security).requireSystemAdmin();assertThrows(BizException.class,()->service.get(null));
        reset(security);assertThrows(BizException.class,()->service.get(-1));assertThrows(BizException.class,()->service.get(100));
    }
}

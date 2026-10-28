package io.studentcloud.content.statistics;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class FileBusinessStatisticsTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"),ZoneOffset.UTC);
    private final Actor admin=new Actor(1,"a1","global","a@example.test",Role.ADMIN,null);
    private final Actor manager=new Actor(2,"a2","manager","b@example.test",Role.TENANT_ADMIN,1L);
    private final Actor member=new Actor(3,"a3","member","c@example.test",Role.USER,1L);
    private JdbcTemplate jdbc;
    private FileBusinessStatistics stats;
    private final Map<Long,Actor> identities=new HashMap<>();
    @BeforeEach void setup(){
        var database=new JdbcDataSource();database.setURL("jdbc:h2:mem:file_stats_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-courses-schema.sql"),new ClassPathResource("db/independent-course-materials-schema.sql"),new ClassPathResource("db/independent-uploads-schema.sql"),new ClassPathResource("db/independent-shares-schema.sql"),new ClassPathResource("db/independent-recycle-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);for(var actor:List.of(admin,manager,member))identities.put(actor.id(),actor);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){var actor=identities.get(id);if(actor==null)throw new ApplicationFault("42000",401,"disabled");return actor;}public boolean workspaceEnabled(long id){return id==1;}};
        stats=new FileBusinessStatistics(jdbc,clock,accounts);
        jdbc.update("INSERT INTO sc_drive_mount(id,mount_key,name,workspace_id,owner_id,engine,physical_root,enabled,revision,created_at) VALUES(1,'one-drive','One',1,2,'LOCAL','private/root/one',TRUE,1,?),(2,'two-drive','Two',2,4,'LOCAL','private/root/two',FALSE,1,?)",clock.millis(),clock.millis());
        jdbc.update("INSERT INTO sc_shared_course(id,owner_id,name,description,quota_bytes,enabled,created_at,updated_at) VALUES(1,2,'private course A','',1000,TRUE,?,?),(2,4,'private course B','',1000,TRUE,?,?),(3,2,'private course C','',1000,FALSE,?,?)",clock.millis(),clock.millis(),clock.millis(),clock.millis(),clock.millis(),clock.millis());
        jdbc.update("INSERT INTO sc_course_material(course_id,filename,object_key,size_bytes,state,created_at,updated_at) VALUES(1,'private.txt','obj-a',10,'READY',?,?),(1,'pending.txt','obj-b',100,'PENDING',?,?),(2,'other.txt','obj-c',80,'READY',?,?)",clock.millis(),clock.millis(),clock.millis(),clock.millis(),clock.millis(),clock.millis());
        upload("upload-1",2,1,"OPEN",50,clock.millis()+1000);upload("upload-2",4,2,"OPEN",70,clock.millis()-1);upload("upload-3",4,2,"COMPLETED",0,clock.millis()+1000);
        share("a",1,null,clock.millis()+1000);share("b",1,clock.millis(),clock.millis()+1000);share("c",1,null,clock.millis()-1);share("d",2,null,clock.millis()+1000);
        for(String state:List.of("TRASHED","PREPARING","RESTORING","UNCERTAIN","PURGING","RESTORED","PURGED"))trash(state,1,10);
        trash("TRASHED",2,20);
    }
    private void upload(String id,long owner,long workspace,String state,long reserved,long expires){jdbc.update("INSERT INTO sc_upload_session(id,owner_id,workspace_id,node_id,mount_key,target_path,byte_size,staging_reserved,manifest_hash,authorization_hash,staging_identity,state,created_at,expires_at) VALUES(?,?,?,'node','one-drive','/secret/file.txt',100,?,'manifest','auth','identity',?,?,?)",id,owner,workspace,reserved,state,clock.millis(),expires);}
    private void share(String id,long workspace,Long revoked,long expires){jdbc.update("INSERT INTO sc_file_share VALUES(?,2,?,'one-drive','/secret/file.txt','secret.txt',10,?,'hash','OWN_WORKSPACE',NULL,?,?,?,1)",id.repeat(43),workspace,clock.millis(),expires,clock.millis(),revoked);}
    private void trash(String state,long workspace,long bytes){jdbc.update("INSERT INTO sc_drive_trash VALUES(?,2,?,1,?,'/admins/u2','/secret/file.txt','secret.txt',FALSE,?,?,?, ?,?)",UUID.randomUUID().toString().replace("-",""),workspace,"a".repeat(64),bytes,state,clock.millis(),clock.millis()-1,clock.millis());}
    @Test void globalCountsAreRealOwnedDatabaseAggregatesWithoutPrivateMetadata(){
        var result=stats.overview(admin,7);assertEquals("GLOBAL",result.scope());assertNull(result.workspaceId());
        assertEquals(2,result.metrics().get("mounts").value());assertEquals(1,result.metrics().get("enabledMounts").value());
        assertEquals(3,result.metrics().get("courses").value());assertEquals(2,result.metrics().get("readyMaterials").value());assertEquals(90,result.metrics().get("materialBytes").value());
        assertEquals(1,result.metrics().get("activeUploads").value());assertEquals(120,result.metrics().get("stagingBytes").value());assertEquals(2,result.metrics().get("activeShares").value());
        assertEquals(6,result.metrics().get("trashItems").value());assertEquals(70,result.metrics().get("trashBytes").value());assertTrue(result.dataComplete());assertTrue(result.resourceAvailability().get("trash"));
        assertEquals(4,result.trends().get("shares").days().getLast().count());assertEquals(7,result.trends().get("shares").days().size());
        assertFalse(result.toString().contains("private course"));assertFalse(result.toString().contains("secret"));assertFalse(result.toString().contains("private/root"));
    }
    @Test void workspaceManagersUseTheirFreshWorkspaceAndOnlyTheirOwnCreatedCourses(){
        var result=stats.overview(manager,7);assertEquals("WORKSPACE",result.scope());assertEquals(1L,result.workspaceId());assertEquals("OWN_CREATED",result.courseScope());
        assertEquals(1,result.metrics().get("mounts").value());assertEquals(2,result.metrics().get("courses").value());assertEquals(10,result.metrics().get("materialBytes").value());
        assertEquals(1,result.metrics().get("activeShares").value());assertEquals(1,result.trends().get("uploads").days().getLast().count());
        assertEquals(5,result.metrics().get("trashItems").value());assertEquals(50,result.metrics().get("trashBytes").value());
    }
    @Test void freshIdentityPreventsForgedRolesAndAnonymousOrMemberStatistics(){
        assertEquals(401,assertThrows(ApplicationFault.class,()->stats.overview(null,7)).httpStatus());
        var forged=new Actor(member.id(),"forged","forged",null,Role.ADMIN,null);
        assertEquals(403,assertThrows(ApplicationFault.class,()->stats.overview(forged,7)).httpStatus());
        identities.put(manager.id(),member);assertEquals(403,assertThrows(ApplicationFault.class,()->stats.overview(manager,7)).httpStatus());
    }
    @Test void unknownResourcesAreUnavailableRatherThanZeroWhileOtherSourcesStillLoad(){
        jdbc.execute("DROP TABLE sc_upload_chunk");jdbc.execute("DROP TABLE sc_upload_session");
        var result=stats.overview(admin,7);assertFalse(result.metrics().get("activeUploads").available());assertNull(result.metrics().get("activeUploads").value());
        assertFalse(result.trends().get("uploads").available());assertTrue(result.metrics().get("mounts").available());assertEquals(2,result.metrics().get("mounts").value());
        assertFalse(result.dataComplete());assertFalse(result.resourceAvailability().get("uploads"));
    }
    @Test void trendDaysAreBoundedAndUseShanghaiDayBoundaries(){
        assertEquals(400,assertThrows(ApplicationFault.class,()->stats.overview(admin,33)).httpStatus());
        long previousUtcDayButCurrentShanghaiDay=Instant.parse("2026-10-25T17:00:00Z").toEpochMilli();
        jdbc.update("UPDATE sc_upload_session SET created_at=? WHERE id='upload-1'",previousUtcDayButCurrentShanghaiDay);
        var days=stats.overview(admin,1).trends().get("uploads").days();assertEquals("2026-10-26",days.getFirst().date());assertEquals(3,days.getFirst().count());
    }
}

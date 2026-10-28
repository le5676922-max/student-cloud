package io.studentcloud.content.sharing;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.course.SharedCourseService;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.application.MountService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ControlledShareServiceTest {
    @TempDir Path temp;
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneOffset.UTC);
    private final Actor admin=new Actor(1,"a1","admin","a@example.test",Role.ADMIN,null);
    private final Actor owner=new Actor(2,"a2","owner","o@example.test",Role.TENANT_ADMIN,1L);
    private final Actor member=new Actor(3,"a3","member","m@example.test",Role.USER,1L);
    private final Actor outsider=new Actor(4,"a4","other","x@example.test",Role.USER,2L);
    private final Map<Long,Actor> identities=new ConcurrentHashMap<>();
    private JdbcTemplate jdbc;
    private ControlledShareService shares;
    private SharedCourseService courses;
    private Path own;

    @BeforeEach void setup()throws Exception {
        var db=new JdbcDataSource(); db.setURL("jdbc:h2:mem:shares_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-courses-schema.sql"),new ClassPathResource("db/independent-shares-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);
        for(Actor actor:List.of(admin,owner,member,outsider))identities.put(actor.id(),actor);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){Actor value=identities.get(id);if(value==null)throw new ApplicationFault("42000",401,"disabled");return value;}public boolean workspaceEnabled(long id){return id==1||id==2;}};
        Path managed=Files.createDirectory(temp.resolve("managed"));Path mounted=Files.createDirectory(managed.resolve("drive"));own=Files.createDirectories(mounted.resolve("admins/u2"));
        Files.writeString(own.resolve("note.txt"),"private file");
        var mounts=new MountService(jdbc,tx,clock,managed,accounts);var mount=mounts.createLocal(admin,"private","private-drive",mounted,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(2,?,'/admins/u2','list,read',TRUE,1,1)",mount.id());
        courses=new SharedCourseService(jdbc,tx,clock,accounts);
        shares=new ControlledShareService(jdbc,tx,clock,accounts,mounts,courses,managed);
    }
    private ControlledShareService.ShareView create(){return shares.create(owner,"private-drive","/note.txt","OWN_WORKSPACE",null,null);}
    private void status(int expected,Runnable action){assertEquals(expected,assertThrows(ApplicationFault.class,action::run).httpStatus());}

    @Test void loggedInWorkspaceAudienceRequiresConsentEquivalentExplicitScopeAndNeverGrantsGlobalAdminAccess()throws Exception {
        var share=create();assertTrue(share.id().matches("[A-Za-z0-9_-]{43}"));assertEquals(clock.millis()+7L*86400000,share.expiresAt());
        assertEquals("note.txt",shares.get(member,share.id()).name());
        try(var content=shares.open(member,share.id())){assertEquals("private file",new String(content.input().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        status(401,()->shares.get(null,share.id()));status(403,()->shares.get(outsider,share.id()));status(403,()->shares.get(admin,share.id()));
        status(401,()->shares.open(null,share.id()));status(403,()->shares.open(outsider,share.id()));
        status(403,()->shares.create(admin,"private-drive","/note.txt","OWN_WORKSPACE",null,null));
        assertTrue(shares.list(outsider,0,50).isEmpty());
        assertFalse(shares.get(member,share.id()).toString().contains("/admins/u2"));
    }
    @Test void revokeIsVersionedAndDoesNotDeleteTheFile(){
        var share=create();status(403,()->shares.revoke(member,share.id(),share.version()));
        status(409,()->shares.revoke(owner,share.id(),share.version()+1));
        shares.revoke(owner,share.id(),share.version());status(410,()->shares.get(member,share.id()));assertTrue(Files.exists(own.resolve("note.txt")));
        status(409,()->shares.revoke(owner,share.id(),share.version()));
        var second=create();assertEquals(second.id(),shares.list(owner,0,100).getFirst().id());shares.revoke(admin,second.id(),second.version());status(410,()->shares.get(member,second.id()));
    }
    @Test void expiryOwnerDisabledWorkspaceChangeAndFreshPermissionAreEnforced(){
        var share=create();jdbc.update("UPDATE sc_file_share SET expires_at=? WHERE share_id=?",clock.millis(),share.id());status(410,()->shares.get(member,share.id()));
        var next=create();jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");status(403,()->shares.get(member,next.id()));
        jdbc.update("UPDATE sc_drive_grant SET permissions='list,read',revision=revision+1");
        identities.put(owner.id(),new Actor(owner.id(),owner.accountId(),owner.username(),owner.email(),owner.role(),2L));status(403,()->shares.get(member,next.id()));
        identities.remove(owner.id());status(403,()->shares.get(member,next.id()));
    }
    @Test void identityTransportCannotForgeAnOwnersGrant(){
        Actor forged=new Actor(member.id(),owner.accountId(),owner.username(),owner.email(),Role.TENANT_ADMIN,owner.workspaceId());
        status(403,()->shares.create(forged,"private-drive","/note.txt","OWN_WORKSPACE",null,null));
        var share=create();status(403,()->shares.revoke(forged,share.id(),share.version()));
    }
    @Test void replacementWithSameSizeAndMtimeAndMovedSourcesNeverServeNewContent()throws Exception {
        var share=create();var modified=Files.getLastModifiedTime(own.resolve("note.txt"));
        Files.writeString(own.resolve("note.txt"),"changed file");Files.setLastModifiedTime(own.resolve("note.txt"),modified);
        status(409,()->shares.get(member,share.id()));status(409,()->{try(var ignored=shares.open(member,share.id())){}catch(java.io.IOException e){throw new RuntimeException(e);}});
        Files.move(own.resolve("note.txt"),own.resolve("moved.txt"));status(409,()->shares.get(member,share.id()));
    }
    @Test void courseAudienceRequiresBothOwnersAndRecipientsCurrentMembership(){
        var course=courses.create(owner,"course","",1024*1024,true);var invitation=courses.createInvitation(owner,course.id(),null,null);courses.join(outsider,invitation.code());
        var share=shares.create(owner,"private-drive","/note.txt","COURSE",course.id(),null);
        assertEquals(share.id(),shares.get(outsider,share.id()).id());status(403,()->shares.get(member,share.id()));
        courses.leave(outsider,course.id());status(403,()->shares.get(outsider,share.id()));
        courses.update(owner,course.id(),"course","",1024*1024,false);status(403,()->shares.get(owner,share.id()));
    }
    @Test void directoriesInvalidAudiencesAndUnboundedLifetimesAreRejected()throws Exception {
        Files.createDirectory(own.resolve("folder"));status(400,()->shares.create(owner,"private-drive","/folder","OWN_WORKSPACE",null,null));
        status(400,()->shares.create(owner,"private-drive","/note.txt","PUBLIC",null,null));
        status(400,()->shares.create(owner,"private-drive","/note.txt","COURSE",null,null));
        status(400,()->shares.create(owner,"private-drive","/note.txt","OWN_WORKSPACE",null,clock.millis()+31L*86400000));
    }
    @Test void contentIsAuthenticatedRawAttachmentAndNoTokenOrExternalPreviewIsReturned()throws Exception {
        var share=create();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new io.studentcloud.content.http.ControlledShareController(shares))
                .setControllerAdvice(new io.studentcloud.runtime.http.HttpFailures())
                .addFilters(new io.studentcloud.runtime.http.AuthenticationFilter(token->{if(!"member".equals(token))throw new ApplicationFault("42000",401,"login");return member;},new tools.jackson.databind.ObjectMapper()),new io.studentcloud.content.http.ControlledShareBodyFilter(new tools.jackson.databind.ObjectMapper())).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shares/"+share.id()+"/content"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shares/"+share.id()+"/content").header("Authorization","Bearer member"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("private file"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/octet-stream"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shares/"+share.id()).header("Authorization","Bearer member"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("token"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("source_path"))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/shares").header("Authorization","Bearer member").contentType("application/json").content("{\"padding\":\""+"x".repeat(9000)+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge());
    }
    @Test void ownerQuotaIsSerializedAcrossConcurrentRequests()throws Exception {
        // Existing active rows count even when they predate the per-minute creation budget.
        var seed=create();for(int n=1;n<99;n++)jdbc.update("INSERT INTO sc_file_share SELECT ?,owner_account,workspace_id,mount_key,source_path,file_name,file_size,modified_at,content_hash,audience,course_id,expires_at,created_at-120000,revoked_at,version FROM sc_file_share WHERE share_id=?",String.format("%043d",n),seed.id());
        jdbc.update("UPDATE sc_file_share SET created_at=created_at-120000");
        var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<Integer>> results=new ArrayList<>();for(int n=0;n<2;n++)results.add(pool.submit(()->{start.await();try{create();return 200;}catch(ApplicationFault denied){return denied.httpStatus();}}));start.countDown();
            var statuses=new ArrayList<Integer>();for(var result:results)statuses.add(result.get(10,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        }
        assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_share WHERE revoked_at IS NULL",Integer.class));
    }
    @Test void boundedContentPermitsAreHeldUntilTheHttpAdapterClosesTheResponse()throws Exception {
        var share=create();
        try(var first=shares.open(member,share.id());var second=shares.open(member,share.id())){
            status(429,()->shares.open(member,share.id()));
        }
        try(var again=shares.open(member,share.id())){assertEquals(12,again.size());}
    }
}

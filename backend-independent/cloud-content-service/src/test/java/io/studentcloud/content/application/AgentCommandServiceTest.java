package io.studentcloud.content.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.*;
import io.studentcloud.drive.application.MountService;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AgentCommandServiceTest {
    @TempDir Path temporary;
    Actor owner=new Actor(2,"owner","owner",null,Role.TENANT_ADMIN,1L);
    Actor member=new Actor(3,"member","member",null,Role.USER,1L);
    Map<Long,Actor> identities=new HashMap<>();
    JdbcTemplate jdbc;MountService mounts;AgentCommandService commands;Path privateRoot;
    io.studentcloud.content.learning.LearningLibraryService learning;
    @BeforeEach void setup() throws Exception {
        Path managed=Files.createDirectory(temporary.resolve("managed"));
        Path physical=Files.createDirectory(managed.resolve("cloud"));
        privateRoot=Files.createDirectories(physical.resolve("members/u3"));
        Files.createDirectories(physical.resolve("admins/u2"));
        Files.writeString(privateRoot.resolve("notes.txt"),"original");
        var database=new JdbcDataSource();database.setURL("jdbc:h2:mem:agentcmd_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-agent-commands-schema.sql"),new ClassPathResource("db/independent-learning-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);var tx=new DataSourceTransactionManager(database);
        identities.put(1L,new Actor(1,"root","root",null,Role.ADMIN,null));identities.put(2L,owner);identities.put(3L,member);
        AccountDirectory accounts=new AccountDirectory(){
            public Actor account(long id){var actor=identities.get(id);if(actor==null)throw new ApplicationFault("42000",401,"账号不可用");return actor;}
            public boolean workspaceEnabled(long id){return id==1;}
        };
        mounts=new MountService(jdbc,tx,Clock.systemUTC(),managed,accounts);
        var mount=mounts.createLocal(identities.get(1L),"Files","test-drive",physical,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,mkdir,upload,copy,move,rename,delete',TRUE,1,1)",mount.id());
        learning=new io.studentcloud.content.learning.LearningLibraryService(jdbc,tx,Clock.systemUTC(),accounts,mounts);
        commands=new AgentCommandService(jdbc,tx,Clock.systemUTC(),accounts,mounts,new ObjectMapper(),learning);
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}
    AgentCommandService.Preview preview(String op,String source,String target){
        return commands.preview(3,1L,"test-drive",List.of(new AgentCommandService.Step(op,source,target)));
    }
    @Test void previewDoesNotMutateAndConfirmedExecutionIsIdempotent() throws Exception {
        var plan=preview("COPY","/notes.txt","/copy.txt");
        assertFalse(Files.exists(privateRoot.resolve("copy.txt")));
        var result=commands.execute(3,1L,plan.previewToken(),"copy-test");
        assertEquals("COMPLETED",result.status());assertEquals("original",Files.readString(privateRoot.resolve("copy.txt")));
        assertFalse(result.replayed());assertTrue(commands.execute(3,1L,plan.previewToken(),"copy-test").replayed());
        assertThrows(ApplicationFault.class,()->commands.execute(3,1L,plan.previewToken(),"different-key"));
    }
    @Test void changedSourceOrRevokedPermissionCannotExecutePreview() throws Exception {
        var plan=preview("MOVE","/notes.txt","/moved.txt");
        Files.writeString(privateRoot.resolve("notes.txt"),"changed source size");
        assertEquals(409,assertThrows(ApplicationFault.class,()->commands.execute(3,1L,plan.previewToken(),"changed")).httpStatus());
        assertTrue(Files.exists(privateRoot.resolve("notes.txt")));assertFalse(Files.exists(privateRoot.resolve("moved.txt")));
        var revoked=preview("COPY","/notes.txt","/copy.txt");
        jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");
        assertEquals(403,assertThrows(ApplicationFault.class,()->commands.execute(3,1L,revoked.previewToken(),"revoked")).httpStatus());
    }
    @Test void planIsBoundToOwnerWorkspaceExpiryAndAllowedOperations(){
        var plan=preview("MKDIR",null,"/new");
        assertThrows(ApplicationFault.class,()->commands.execute(2,1L,plan.previewToken(),"not-owner"));
        assertThrows(ApplicationFault.class,()->commands.execute(3,2L,plan.previewToken(),"wrong-space"));
        jdbc.update("UPDATE sc_agent_command SET expires_at=0");
        assertEquals(409,assertThrows(ApplicationFault.class,()->commands.execute(3,1L,plan.previewToken(),"expired")).httpStatus());
        assertThrows(ApplicationFault.class,()->preview("DELETE","/notes.txt",null));
        assertThrows(ApplicationFault.class,()->preview("MOVE","/../notes.txt","/new"));
    }
    @Test void interruptedClaimIsNeverAutomaticallyReplayed(){
        var plan=preview("MKDIR",null,"/new");
        jdbc.update("UPDATE sc_agent_command SET status='RUNNING',idempotency_key='crashed',started_at=?",System.currentTimeMillis()-200_000);
        assertEquals("INDETERMINATE",commands.execute(3,1L,plan.previewToken(),"crashed").status());
        assertFalse(Files.exists(privateRoot.resolve("new")));
    }
    @Test void bulkOrganizePreviewsAndExecutesFolderCreationBeforeMovingFiles() throws Exception {
        var plan=commands.previewBulk(3,1L,"test-drive","ORGANIZE_FILES","/");
        assertEquals("MKDIR",plan.steps().getFirst().op());
        assertEquals("COMPLETED",commands.execute(3,1L,plan.previewToken(),"bulk-test").status());
        assertEquals("original",Files.readString(privateRoot.resolve("文档/notes.txt")));
    }
    @Test void learningImportRequiresConfirmationAndOwnedCourseAndNeverMovesSource() throws Exception {
        var course=learning.createCourse(member,"数据库","大四");
        var plan=commands.previewBulk(3,1L,"test-drive","IMPORT_LEARNING","/",course.id(),"复习");
        assertTrue(learning.listMaterials(member,course.id()).isEmpty());
        assertEquals("COMPLETED",commands.execute(3,1L,plan.previewToken(),"import-once").status());
        assertEquals(1,learning.listMaterials(member,course.id()).size());
        assertEquals("original",Files.readString(privateRoot.resolve("notes.txt")));
        assertTrue(commands.execute(3,1L,plan.previewToken(),"import-once").replayed());
        assertEquals(1,learning.listMaterials(member,course.id()).size());
        assertThrows(ApplicationFault.class,()->commands.previewBulk(3,1L,"test-drive","IMPORT_LEARNING","/",course.id(),null));
        var otherCourse=learning.createCourse(owner,"私有课程",null);
        assertThrows(ApplicationFault.class,()->commands.previewBulk(3,1L,"test-drive","IMPORT_LEARNING","/",otherCourse.id(),null));
    }
    @Test void deletingCourseAfterPreviewCannotImportIntoAnotherCourse(){
        var course=learning.createCourse(member,"课程",null);
        var plan=commands.previewBulk(3,1L,"test-drive","IMPORT_LEARNING","/",course.id(),null);
        learning.deleteCourse(member,course.id());
        assertEquals("FAILED",commands.execute(3,1L,plan.previewToken(),"deleted-course").status());
    }
    @Test void duplicateReportComparesBytesWithoutDeletingAndCannotReadRevokedRoots() throws Exception {
        Files.writeString(privateRoot.resolve("duplicate.txt"),"original");
        Files.writeString(privateRoot.resolve("different.txt"),"changed!");
        var report=commands.duplicates(3,1L,"test-drive","/");
        assertEquals(3,report.checkedFiles());assertEquals(1,report.groups().size());
        assertEquals(Set.of("/notes.txt","/duplicate.txt"),Set.copyOf(report.groups().getFirst().paths()));
        try(var files=Files.list(privateRoot)){assertEquals(3,files.count());}
        jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");
        var blocked=commands.duplicates(3,1L,"test-drive","/");
        assertEquals(0,blocked.checkedFiles());assertEquals(3,blocked.skippedFiles());assertTrue(blocked.groups().isEmpty());
    }
    @Test void unreadableDuplicateCandidatesStillConsumeTheReadAttemptBudget() throws Exception {
        for(int i=0;i<100;i++)Files.writeString(privateRoot.resolve("file-"+i+".txt"),"x");
        jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");
        // Read denials must consume a slot too, rather than probing every one of 10,000 entries.
        var report=commands.duplicates(3,1L,"test-drive","/");
        assertEquals(50,report.attemptedFiles());assertEquals(101,report.skippedFiles());
    }
}

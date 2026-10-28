package io.studentcloud.content.http;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.application.*;
import io.studentcloud.runtime.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ResumableUploadControllerTest {
    @TempDir Path temp;
    MockMvc mvc;ResumableUploadService service;JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper();
    Actor member=new Actor(3,"a3","member",null,Role.USER,1L);
    @BeforeEach void setup()throws Exception{
        Path managed=Files.createDirectory(temp.resolve("managed")),root=Files.createDirectory(managed.resolve("one"));
        Files.createDirectories(root.resolve("members/u3"));
        Path stage=Files.createDirectory(temp.resolve("stage"));
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:upload_http_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-uploads-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);
        Actor admin=new Actor(1,"a1","admin",null,Role.ADMIN,null);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:member;}public boolean workspaceEnabled(long id){return id==1;}};
        var mounts=new MountService(jdbc,tx,Clock.systemUTC(),managed,accounts);
        var mount=mounts.createLocal(admin,"drive","local-drive",root,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,upload',TRUE,1,1)",mount.id());
        service=new ResumableUploadService(jdbc,tx,Clock.systemUTC(),mounts,stage,managed,null,"node-a");
        mvc=MockMvcBuilders.standaloneSetup(new ResumableUploadController(service)).setControllerAdvice(new HttpFailures())
                .addFilters(new AuthenticationFilter(token->{if(!"member".equals(token))throw new ApplicationFault("42000",401,"请先登录");return member;},json)).build();
    }
    @Test void requiresHeaderAuthenticationAndDoesNotTrustQueryTokens()throws Exception{
        String payload=json.writeValueAsString(Map.of("storageKey","local-drive","path","/","name","one","size",3));
        mvc.perform(post("/api/uploads/resumable/capabilities").contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/uploads/resumable/capabilities").param("token","member").contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/uploads/resumable/capabilities").header("Authorization","Bearer member").contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.protocol").value("independent-v1")).andExpect(jsonPath("$.data.maxFileSize").value(50*1024*1024));
    }
    @Test void rawChunkEndpointVerifiesDigestAndIdempotentlyCompletes()throws Exception{
        byte[] bytes={1,2,3};String hash=ResumableUploadService.sha256(bytes);
        var session=service.init(member,"local-drive",io.studentcloud.drive.DrivePath.of("/one"),3,List.of(hash),ResumableUploadService.manifestHash(List.of(hash)));
        mvc.perform(put("/api/uploads/resumable/"+session.id()+"/chunks/0").header("Authorization","Bearer member").header("X-Upload-Chunk-SHA256",hash).contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.missingChunks.length()").value(0));
        for(int i=0;i<2;i++)mvc.perform(post("/api/uploads/resumable/"+session.id()+"/complete").header("Authorization","Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("COMPLETED"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_upload_session WHERE state='COMPLETED'",Integer.class));
    }
}

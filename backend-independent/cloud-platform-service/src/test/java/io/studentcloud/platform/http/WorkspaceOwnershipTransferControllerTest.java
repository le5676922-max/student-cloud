package io.studentcloud.platform.http;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.platform.config.OwnershipTransferConfiguration;
import io.studentcloud.runtime.http.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class WorkspaceOwnershipTransferControllerTest {
    JdbcTemplate jdbc;AccountService accounts;WorkspaceOwnershipTransferService service;MockMvc mvc;String ownerToken,recipientToken,otherToken;Actor owner;long now;
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:handover_http_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-workspace-ownership-transfer-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);var manager=new DataSourceTransactionManager(db);var clock=Clock.systemUTC();now=clock.millis();
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'Synthetic space',TRUE,0),(2,'Other space',TRUE,0)");
        accounts=new AccountService(jdbc,new TransactionTemplate(manager),clock,Duration.ofHours(24));
        accounts.install("Synthetic platform","synthetic-root","Synthetic-password-123");
        String hash=jdbc.queryForObject("SELECT password_hash FROM sc_account WHERE username='synthetic-root'",String.class);
        jdbc.update("DELETE FROM sc_account");
        for(int id=1;id<=3;id++)jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,created_at) VALUES(?,?,?,?,?,?,0)",id,"SC"+id,"user"+id,hash,id==1?"TENANT_ADMIN":"USER",id==3?2:1);
        accounts=new AccountService(jdbc,new TransactionTemplate(manager),clock,Duration.ofHours(24));
        ownerToken=accounts.login("user1","Synthetic-password-123").token();recipientToken=accounts.login("user2","Synthetic-password-123").token();otherToken=accounts.login("user3","Synthetic-password-123").token();owner=accounts.authenticate(ownerToken);
        service=new WorkspaceOwnershipTransferService(jdbc,manager,clock,true);
        mvc=MockMvcBuilders.standaloneSetup(new WorkspaceOwnershipTransferController(service)).setControllerAdvice(new HttpFailures()).addFilters(new AuthenticationFilter(accounts::authenticate,new ObjectMapper())).build();
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}
    @Test void recipientCanReadInboxAndAcceptButSourceCannotAcceptAndOldBearerIsRevoked()throws Exception{
        var row=service.create(owner,2,0,now+60000,"http");String path="/api/workspace-admin/ownership-transfers/"+row.id()+"/accept";
        assertEquals(403,mvc.perform(post(path).header("Authorization","Bearer "+ownerToken)).andReturn().getResponse().getStatus());
        var inbox=mvc.perform(get("/api/workspace-admin/ownership-transfers").header("Authorization","Bearer "+recipientToken)).andReturn().getResponse();
        assertEquals(200,inbox.getStatus());assertTrue(inbox.getContentAsString().contains(row.id()));assertEquals("no-store",inbox.getHeader("Cache-Control"));
        var accepted=mvc.perform(post(path).header("Authorization","Bearer "+recipientToken)).andReturn().getResponse();assertEquals(200,accepted.getStatus());assertTrue(accepted.getContentAsString().contains("COMPLETED"));
        assertEquals(200,mvc.perform(post(path).header("Authorization","Bearer "+recipientToken)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/api/workspace-admin/ownership-transfers").header("Authorization","Bearer "+ownerToken)).andReturn().getResponse().getStatus());
    }
    @Test void unauthenticatedCrossWorkspaceAndInvalidInputsAreRejected()throws Exception{
        String path="/api/workspace-admin/ownership-transfers";var row=service.create(owner,2,0,now+60000,"http");
        assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post(path+"/"+row.id()+"/accept").header("Authorization","Bearer "+otherToken)).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(post(path).header("Authorization","Bearer "+ownerToken).contentType("application/json").content("{\"toAccountId\":-1,\"expectedRevision\":0,\"expiresAt\":1,\"idempotencyKey\":\"test\"}")).andReturn().getResponse().getStatus());
        assertThrows(ApplicationFault.class,()->service.create(accounts.authenticate(otherToken),2,0,now+60000,"cross"));
    }
    @Test void controllerAndServiceBeansAreAbsentWhenFlagIsUnspecified(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.register(OwnershipTransferConfiguration.class,WorkspaceOwnershipTransferController.class);context.refresh();
            assertEquals(0,context.getBeanNamesForType(WorkspaceOwnershipTransferService.class).length);
            assertEquals(0,context.getBeanNamesForType(WorkspaceOwnershipTransferController.class).length);
        }
    }
}

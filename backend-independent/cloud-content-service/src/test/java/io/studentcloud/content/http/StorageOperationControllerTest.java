package io.studentcloud.content.http;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.application.*;
import io.studentcloud.runtime.http.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageOperationControllerTest {
    MockMvc mvc;StorageOperationJournal journal;String id;boolean granted=true;
    Actor member=new Actor(3,"a3","member",null,Role.USER,1L),other=new Actor(4,"a4","other",null,Role.USER,1L);
    @BeforeEach void setup() {
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:journal_http_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-storage-operations-schema.sql")).execute(db);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==3?member:other;}public boolean workspaceEnabled(long id){return id==1;}};
        DriveOperations drives=(DriveOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DriveOperations.class},(p,m,a)->{
            if(m.getName().equals("listMounts"))return granted?List.of(new MountView(8,"drive","盘",1L,1,"LOCAL",true,1,Set.of("read","upload"))):List.of();
            throw new UnsupportedOperationException();
        });
        journal=new StorageOperationJournal(new JdbcTemplate(db),new DataSourceTransactionManager(db),Clock.systemUTC(),accounts,drives);
        id=journal.prepare(member,"drive","UPLOAD","synthetic","private-path-not-in-response").id();
        mvc=MockMvcBuilders.standaloneSetup(new StorageOperationController(journal)).setControllerAdvice(new HttpFailures())
            .addFilters(new AuthenticationFilter(token->{if("member".equals(token))return member;if("other".equals(token))return other;throw new ApplicationFault("42000",401,"请先登录");},new ObjectMapper())).build();
    }
    @Test void ownerReceiptIsNoStoreAndHasNoRequestOrPhysicalPath()throws Exception {
        mvc.perform(get("/api/drives/drive/operations/"+id).header("Authorization","Bearer member"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.state").value("PREPARED"))
            .andExpect(jsonPath("$.data.requestHash").doesNotExist()).andExpect(jsonPath("$.data.physicalRoot").doesNotExist());
        mvc.perform(get("/api/drives/drive/operations").header("Authorization","Bearer member").param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
    }
    @Test void headerAuthenticationOwnerScopeGrantAndBoundsAreCheckedEveryTime()throws Exception {
        mvc.perform(get("/api/drives/drive/operations").param("token","member")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/drives/drive/operations/"+id).header("Authorization","Bearer other")).andExpect(status().isNotFound());
        mvc.perform(get("/api/drives/drive/operations").header("Authorization","Bearer other")).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/drives/drive/operations").header("Authorization","Bearer member").param("limit","51")).andExpect(status().isBadRequest());
        granted=false;
        mvc.perform(get("/api/drives/drive/operations/"+id).header("Authorization","Bearer member")).andExpect(status().isForbidden());
    }
}

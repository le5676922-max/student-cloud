package io.studentcloud.platform.http;

import io.studentcloud.account.*;
import io.studentcloud.platform.mail.MailSettings;
import io.studentcloud.platform.mail.SmtpEmailCodeSender;
import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.HttpFailures;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real MVC conversion/advice and authentication around an isolated account database; no network email. */
class WorkspaceAccountControllerTest {
    private static final String PASSWORD="Synthetic-password-123";
    private JdbcTemplate jdbc;
    private AccountAdministration administration;
    private AccountService accounts;
    private Actor global,owner;
    private String globalToken,ownerToken;
    private FakeMail mail;
    private MockMvc mvc;

    @BeforeEach void setup(){
        var database=new DriverManagerDataSource("jdbc:h2:mem:managed_reset_http_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-email-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql"),new ClassPathResource("db/independent-public-profile-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);var manager=new DataSourceTransactionManager(database);var clock=Clock.systemUTC();
        mail=new FakeMail();var codes=new EmailCodeService(jdbc,manager,clock,mail,"synthetic-internal-key-12345678901234567890");
        var enrollment=new WorkspaceEnrollmentService(jdbc,manager,clock);
        var registrations=new PublicRegistrationService(jdbc,manager,clock,codes,enrollment,true,true);
        administration=new AccountAdministration(jdbc,manager,clock,enrollment::assertMemberActivationAllowed);
        accounts=new AccountService(jdbc,new TransactionTemplate(manager),clock,Duration.ofHours(24));
        accounts.install("Synthetic platform","root-admin",PASSWORD);
        globalToken=accounts.login("root-admin",PASSWORD).token();global=accounts.authenticate(globalToken);
        var created=administration.createWorkspaceAdministrator(global,"space-owner","owner@example.invalid",PASSWORD,"Synthetic space");
        ownerToken=accounts.login(created.accountId(),PASSWORD).token();owner=accounts.authenticate(ownerToken);
        configureMvc(registrations);
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}

    @Test void authorizedEmptyPostSendsToBoundMailboxWithoutReturningProofOrChangingCredentials()throws Exception{
        var member=member("member@example.invalid");var old=accounts.login(member.email(),PASSWORD);
        String hash=hash(member.id());long version=version(member.id());
        var result=send(member.id(),ownerToken,null);
        assertEquals(200,result.getResponse().getStatus());
        String body=result.getResponse().getContentAsString();
        assertTrue(body.contains("重置邮件已发送"));assertFalse(body.contains(member.email()));
        assertFalse(body.contains(mail.values.get(member.email())));assertTrue(body.contains("\"data\":null"));
        assertEquals(hash,hash(member.id()));assertEquals(version,version(member.id()));
        assertEquals(member.id(),accounts.authenticate(old.token()).id());
        assertEquals(1,mail.values.size());
        var repeated=send(member.id(),ownerToken,null);
        assertEquals(429,repeated.getResponse().getStatus());assertEquals("60",repeated.getResponse().getHeader("Retry-After"));
    }

    @Test void passwordOrMailboxBodyCannotChooseCredentialsOrRedirectMail()throws Exception{
        var member=member("member@example.invalid");String hash=hash(member.id());
        for(String body:new String[]{"{\"password\":\"Administrator-chosen-password\"}","{\"email\":\"attacker@example.invalid\"}","{}","x".repeat(32768)}){
            assertEquals(400,send(member.id(),ownerToken,body).getResponse().getStatus());
        }
        assertEquals(hash,hash(member.id()));assertTrue(mail.values.isEmpty());
    }

    @Test void smtpDeliveryLetsOnlyTheMemberCompleteThePublicResetFlow()throws Exception{
        try(var smtp=new LocalSmtpServer()){
            var manager=new DataSourceTransactionManager(jdbc.getDataSource());
            var clock=Clock.systemUTC();
            var sender=new SmtpEmailCodeSender(new MailSettings(true,"127.0.0.1",smtp.port(),"sender@example.invalid","",false,false,false));
            var codes=new EmailCodeService(jdbc,manager,clock,sender,"synthetic-internal-key-12345678901234567890");
            var registrations=new PublicRegistrationService(jdbc,manager,clock,codes,new WorkspaceEnrollmentService(jdbc,manager,clock),true,true);
            configureMvc(registrations);
            var member=member("member@example.invalid");var old=accounts.login(member.email(),PASSWORD);
            String hash=hash(member.id());long version=version(member.id());
            var sent=send(member.id(),ownerToken,null);
            assertEquals(200,sent.getResponse().getStatus());
            var captured=smtp.message();
            assertTrue(captured.recipient().contains("<member@example.invalid>"));
            var code=java.util.regex.Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher((String)captured.message().getContent());
            assertTrue(code.find(),"SMTP message must contain the member's reset proof");
            String proof=code.group();
            assertFalse(sent.getResponse().getContentAsString().contains(proof));
            assertEquals(hash,hash(member.id()));assertEquals(version,version(member.id()));
            assertEquals(member.id(),accounts.authenticate(old.token()).id());
            String payload=new ObjectMapper().writeValueAsString(Map.of("email",member.email(),"emailCode",proof,
                    "password","Member-chosen-password-456","confirmPassword","Member-chosen-password-456"));
            assertEquals(200,mvc.perform(post("/user/password-reset").contentType("application/json").content(payload)).andReturn().getResponse().getStatus());
            assertThrows(io.studentcloud.common.ApplicationFault.class,()->accounts.authenticate(old.token()));
            assertEquals(member.id(),accounts.login(member.email(),"Member-chosen-password-456").account().id());
            assertEquals(400,mvc.perform(post("/user/password-reset").contentType("application/json").content(payload)).andReturn().getResponse().getStatus());
        }
    }

    @Test void smtpMissingAndMailboxMissingAreExplicitAndCannotFallback()throws Exception{
        var withoutEmail=member(null);
        assertEquals(400,send(withoutEmail.id(),ownerToken,null).getResponse().getStatus());
        var member=member("member@example.invalid");String hash=hash(member.id());mail.enabled=false;
        assertEquals(503,send(member.id(),ownerToken,null).getResponse().getStatus());
        assertEquals(hash,hash(member.id()));assertTrue(mail.values.isEmpty());
    }

    @Test void authenticationAndCurrentOwnerScopeApplyToTheMailEndpoint()throws Exception{
        var member=member("member@example.invalid");
        assertEquals(401,send(member.id(),null,null).getResponse().getStatus());
        assertEquals(403,send(member.id(),globalToken,null).getResponse().getStatus());
        String memberToken=accounts.login(member.email(),PASSWORD).token();
        assertEquals(403,send(member.id(),memberToken,null).getResponse().getStatus());
        var other=administration.createWorkspaceAdministrator(global,"other-owner","other@example.invalid",PASSWORD,"Other space");
        String otherToken=accounts.login(other.accountId(),PASSWORD).token();
        assertEquals(404,send(member.id(),otherToken,null).getResponse().getStatus());
        assertEquals(404,send(owner.id(),ownerToken,null).getResponse().getStatus());
        assertTrue(mail.values.isEmpty());
    }

    @Test void legacyPasswordAndCreationPostsAreRetiredWithoutMutations()throws Exception{
        var member=member("member@example.invalid");var old=accounts.login(member.email(),PASSWORD);
        String hash=hash(member.id());int count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_account",Integer.class);
        var password=mvc.perform(post("/api/workspace-admin/members/"+member.id()+"/password").header("Authorization","Bearer "+ownerToken)
                .contentType("application/json").content("{\"password\":\"Administrator-chosen-password-123\"}")).andReturn();
        assertEquals(410,password.getResponse().getStatus());
        assertEquals(hash,hash(member.id()));assertEquals(member.id(),accounts.authenticate(old.token()).id());
        String payload="{\"username\":\"new-member\",\"email\":\"new@example.invalid\",\"password\":\"Administrator-chosen-password-123\"}";
        var create=mvc.perform(post("/api/workspace-admin/members").header("Authorization","Bearer "+ownerToken)
                .contentType("application/json").content(payload)).andReturn();
        assertEquals(410,create.getResponse().getStatus());
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM sc_account",Integer.class));
        assertEquals(403,mvc.perform(post("/api/workspace-admin/members").header("Authorization","Bearer "+globalToken)
                .contentType("application/json").content(payload)).andReturn().getResponse().getStatus());
        assertTrue(mail.values.isEmpty());
    }

    @Test void retiredEndpointsAuthorizeBeforeReturningGoneForAnyOldOrMissingBody()throws Exception{
        var member=member("member@example.invalid");
        String userToken=accounts.login(member.email(),PASSWORD).token();
        var other=administration.createWorkspaceAdministrator(global,"other-owner","other@example.invalid",PASSWORD,"Other space");
        String otherToken=accounts.login(other.accountId(),PASSWORD).token();
        String passwordPath="/api/workspace-admin/members/"+member.id()+"/password";
        for(String body:new String[]{null,"{}","{\"password\":\"short\"}","not-json"}){
            assertEquals(410,postLegacy("/api/workspace-admin/members",ownerToken,body));
            assertEquals(410,postLegacy(passwordPath,ownerToken,body));
            assertEquals(401,postLegacy("/api/workspace-admin/members",null,body));
            assertEquals(401,postLegacy(passwordPath,null,body));
            assertEquals(403,postLegacy("/api/workspace-admin/members",globalToken,body));
            assertEquals(403,postLegacy(passwordPath,globalToken,body));
            assertEquals(403,postLegacy("/api/workspace-admin/members",userToken,body));
            assertEquals(403,postLegacy(passwordPath,userToken,body));
            assertEquals(404,postLegacy(passwordPath,otherToken,body));
        }
        assertTrue(mail.values.isEmpty());
    }

    @Test void disabledOwnerAndDisabledWorkspaceCannotSendResetEmail()throws Exception{
        var member=member("member@example.invalid");
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?",owner.id());
        assertEquals(401,send(member.id(),ownerToken,null).getResponse().getStatus());
        jdbc.update("UPDATE sc_account SET enabled=TRUE WHERE id=?",owner.id());
        jdbc.update("UPDATE sc_workspace SET enabled=FALSE WHERE id=?",owner.workspaceId());
        assertEquals(401,send(member.id(),ownerToken,null).getResponse().getStatus());
        assertTrue(mail.values.isEmpty());
    }

    private int postLegacy(String path,String token,String body)throws Exception{
        var request=post(path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType("application/json").content(body);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private void configureMvc(PublicRegistrationService registrations){
        var controller=new WorkspaceAccountController(administration,new WorkspaceMemberQuery(jdbc,administration),registrations);
        mvc=MockMvcBuilders.standaloneSetup(controller,new PublicRegistrationController(registrations)).setControllerAdvice(new HttpFailures())
                .addFilters(new AuthenticationFilter(accounts::authenticate,new ObjectMapper())).build();
    }

    private MvcResult send(long id,String token,String body)throws Exception{
        var request=post("/api/workspace-admin/members/"+id+"/password-reset-email");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType("application/json").content(body);
        return mvc.perform(request).andReturn();
    }
    private AccountView member(String email){return administration.createMember(owner,"member-"+UUID.randomUUID().toString().substring(0,8),email,PASSWORD);}
    private String hash(long id){return jdbc.queryForObject("SELECT password_hash FROM sc_account WHERE id=?",String.class,id);}
    private long version(long id){return jdbc.queryForObject("SELECT session_version FROM sc_account WHERE id=?",Long.class,id);}
    private static final class FakeMail implements EmailCodeSender{
        boolean enabled=true;
        final Map<String,String> values=new HashMap<>();
        public boolean available(){return enabled;}
        public void send(String email,EmailPurpose purpose,String code){assertEquals(EmailPurpose.RESET_PASSWORD,purpose);values.put(email,code);}
    }
}

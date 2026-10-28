package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PublicRegistrationServiceTest {
    private static final String PASSWORD="Synthetic-password-123";
    private JdbcTemplate jdbc;private AccountService accounts;private PublicRegistrationService registration;
    private WorkspaceEnrollmentService enrollment;private EmailCodeService codes;private FakeMail mail;private AccountAdministration administration;
    @BeforeEach void setup(){
        var database=new DriverManagerDataSource("jdbc:h2:mem:public_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-email-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql"),new ClassPathResource("db/independent-public-profile-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);var tx=new DataSourceTransactionManager(database);var clock=Clock.systemUTC();
        mail=new FakeMail();codes=new EmailCodeService(jdbc,tx,clock,mail,"synthetic-internal-key-12345678901234567890");
        enrollment=new WorkspaceEnrollmentService(jdbc,tx,clock);
        registration=new PublicRegistrationService(jdbc,tx,clock,codes,enrollment,true,true);
        accounts=new AccountService(jdbc,new TransactionTemplate(tx),clock,Duration.ofHours(24));
        administration=new AccountAdministration(jdbc,tx,clock,enrollment::assertMemberActivationAllowed);
        accounts.install("Synthetic platform","root-admin",PASSWORD);
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}
    @Test void publicAdministratorGetsUniqueAccountAndWorkspaceWithoutReview(){
        var created=create("owner@example.invalid","TENANT_ADMIN","Course Space",null,null);
        assertEquals("ACTIVE",created.status());assertEquals("TENANT_ADMIN",created.role());assertTrue(created.accountId().matches("SC[A-F0-9]{32}"));
        var login=accounts.login(created.accountId(),PASSWORD);assertEquals(Role.TENANT_ADMIN,login.account().role());
        assertEquals(created.tenantId(),login.account().workspaceId());assertEquals("Display name",registration.nickname(login.account().id(),"fallback"));
        assertEquals(login.account().id(),accounts.login(created.email(),PASSWORD).account().id());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace_join_request",Integer.class));
    }
    @Test void publicAdminRoleAndMailProofPurposeCannotBeEscalated(){
        registration.requestRegistrationCode("no-admin@example.invalid");
        String code=mail.code("no-admin@example.invalid",EmailPurpose.REGISTER);
        assertEquals(400,assertThrows(ApplicationFault.class,()->registration.register(input("no-admin@example.invalid",code,"ADMIN","Forbidden",null,null))).httpStatus());
        assertNotNull(codes.verify("no-admin@example.invalid",EmailPurpose.REGISTER,code));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE role='ADMIN'",Integer.class));
        assertEquals(400,assertThrows(ApplicationFault.class,()->registration.resetPassword("no-admin@example.invalid",code,PASSWORD,PASSWORD)).httpStatus());
    }
    @Test void nameApplicantCannotLoginOrBypassApprovalAndResetDoesNotActivateIt(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reviewed Space",null,null);
        Actor admin=accounts.authenticate(accounts.login(owner.email(),PASSWORD).token());
        var pending=create("pending@example.invalid","USER",null,"NAME",owner.tenantName());
        assertEquals("PENDING",pending.status());
        assertThrows(ApplicationFault.class,()->accounts.login(pending.email(),PASSWORD));
        long memberId=jdbc.queryForObject("SELECT id FROM sc_account WHERE email=?",Long.class,pending.email());
        assertEquals(409,assertThrows(ApplicationFault.class,()->administration.setMemberEnabled(admin,memberId,true)).httpStatus());
        registration.requestResetCode(pending.email());String reset=mail.code(pending.email(),EmailPurpose.RESET_PASSWORD);
        registration.resetPassword(pending.email(),reset,"Changed-password-123","Changed-password-123");
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=?",Boolean.class,memberId));
        assertThrows(ApplicationFault.class,()->accounts.login(pending.email(),"Changed-password-123"));
        var request=enrollment.joinRequests(admin).getFirst();enrollment.reviewJoinRequest(admin,Long.parseLong(request.id()),true);
        assertEquals(memberId,accounts.login(pending.email(),"Changed-password-123").account().id());
    }
    @Test void invitationEnablesMemberAndPasswordResetRevokesExistingSessions(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Invited Space",null,null);
        Actor admin=accounts.authenticate(accounts.login(owner.email(),PASSWORD).token());
        var invitation=enrollment.createInvitation(admin,null,1);
        var member=create("member@example.invalid","USER",null,"INVITE",invitation.code());
        assertEquals("ACTIVE",member.status());
        var old=accounts.login(member.email(),PASSWORD);registration.requestResetCode(member.email());
        String code=mail.code(member.email(),EmailPurpose.RESET_PASSWORD);
        registration.resetPassword(member.email(),code,"Changed-password-123","Changed-password-123");
        assertThrows(ApplicationFault.class,()->accounts.authenticate(old.token()));
        assertThrows(ApplicationFault.class,()->accounts.login(member.email(),PASSWORD));
        assertEquals(member.accountId(),accounts.login(member.accountId(),"Changed-password-123").account().accountId());
        assertThrows(ApplicationFault.class,()->registration.resetPassword(member.email(),code,PASSWORD,PASSWORD));
    }
    @Test void failedWorkspaceAssignmentRollsBackAccountAndEmailConsumption(){
        create("owner@example.invalid","TENANT_ADMIN","Existing Space",null,null);
        String email="second@example.invalid";registration.requestRegistrationCode(email);String code=mail.code(email,EmailPurpose.REGISTER);
        assertThrows(ApplicationFault.class,()->registration.register(input(email,code,"TENANT_ADMIN","Existing Space",null,null)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE email=?",Integer.class,email));
        assertNotNull(codes.verify(email,EmailPurpose.REGISTER,code));
        assertEquals("ACTIVE",registration.register(input(email,code,"TENANT_ADMIN","Different Space",null,null)).status());
    }
    @Test void adminCanCreateUnverifiedMemberWithoutMailboxAndUnknownResetDoesNotSend(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Managed Space",null,null);
        Actor admin=accounts.authenticate(accounts.login(owner.email(),PASSWORD).token());
        var member=administration.createMember(admin,"managed-member",null,PASSWORD,"Managed display");
        assertNull(member.email());assertEquals("Managed display",registration.nickname(member.id(),"fallback"));
        assertEquals(0,jdbc.queryForObject("SELECT email_verified_at FROM sc_public_profile WHERE account_id=?",Long.class,member.id()));
        int before=mail.values.size();registration.requestResetCode("unknown@example.invalid");assertEquals(before,mail.values.size());
    }
    @Test void knownAndUnknownResetRequestsShareDurableCooldown(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reset Space",null,null);
        for(String email:new String[]{owner.email(),"unknown@example.invalid"}){
            assertDoesNotThrow(()->registration.requestResetCode(email));
            assertEquals(429,assertThrows(ApplicationFault.class,()->registration.requestResetCode(email)).httpStatus());
            assertEquals(1,jdbc.queryForObject("SELECT sent_count FROM sc_email_challenge WHERE email=? AND purpose='RESET_PASSWORD'",Integer.class,email));
        }
        assertNull(mail.code("unknown@example.invalid",EmailPurpose.RESET_PASSWORD));
    }
    @Test void resetDeliveryFailureHasSameAcknowledgementAndCooldownAsUnknownMailbox(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reset Space",null,null);
        mail.fail=true;
        for(String email:new String[]{owner.email(),"unknown@example.invalid"}){
            assertDoesNotThrow(()->registration.requestResetCode(email));
            assertEquals(429,assertThrows(ApplicationFault.class,()->registration.requestResetCode(email)).httpStatus());
            assertNull(jdbc.queryForObject("SELECT code_hash FROM sc_email_challenge WHERE email=? AND purpose='RESET_PASSWORD'",String.class,email));
            assertEquals(400,assertThrows(ApplicationFault.class,()->codes.verify(email,EmailPurpose.RESET_PASSWORD,"123456")).httpStatus());
        }
    }
    @Test void resetActivationFailureHasSameAcknowledgementAsUnknownMailbox(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reset Space",null,null);
        mail.observe=()->jdbc.update("UPDATE sc_email_challenge SET expires_at=0 WHERE purpose='RESET_PASSWORD'");
        assertDoesNotThrow(()->registration.requestResetCode(owner.email()));
        assertDoesNotThrow(()->registration.requestResetCode("unknown@example.invalid"));
        assertEquals(400,assertThrows(ApplicationFault.class,()->codes.verify(owner.email(),EmailPurpose.RESET_PASSWORD,mail.code(owner.email(),EmailPurpose.RESET_PASSWORD))).httpStatus());
    }
    @Test void unknownResetReplacesExistingProofWithUnusableDecoy(){
        String email="unknown@example.invalid";
        codes.request(email,EmailPurpose.RESET_PASSWORD);
        String previousCode=mail.code(email,EmailPurpose.RESET_PASSWORD);
        var previousProof=codes.verify(email,EmailPurpose.RESET_PASSWORD,previousCode);
        jdbc.update("UPDATE sc_email_challenge SET next_send_at=0 WHERE email=?",email);
        int deliveries=mail.deliveries;
        registration.requestResetCode(email);
        assertEquals(deliveries,mail.deliveries);
        assertNull(jdbc.queryForObject("SELECT code_hash FROM sc_email_challenge WHERE email=?",String.class,email));
        assertEquals(400,assertThrows(ApplicationFault.class,()->codes.verify(email,EmailPurpose.RESET_PASSWORD,previousCode)).httpStatus());
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        assertEquals(400,assertThrows(ApplicationFault.class,()->new TransactionTemplate(manager).executeWithoutResult(status->codes.consume(previousProof))).httpStatus());
    }
    @Test void knownAndUnknownResetRequestsShareDailyBudget(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reset Space",null,null);
        for(String email:new String[]{owner.email(),"unknown@example.invalid"}){
            registration.requestResetCode(email);
            jdbc.update("UPDATE sc_email_challenge SET next_send_at=0,sent_count=10 WHERE email=? AND purpose='RESET_PASSWORD'",email);
            assertEquals(429,assertThrows(ApplicationFault.class,()->registration.requestResetCode(email)).httpStatus());
        }
    }
    @Test void globalChallengeCapacityRejectsKnownAndUnknownResetRequestsEqually(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reset Space",null,null);
        jdbc.update("INSERT INTO sc_email_challenge(email,purpose,status,expires_at,attempts,next_send_at,window_started_at,sent_count) SELECT CONCAT('capacity-',X,'@example.invalid'),'RESET_PASSWORD','EMPTY',?,0,0,?,0 FROM SYSTEM_RANGE(1,9999)",System.currentTimeMillis(),System.currentTimeMillis());
        for(String email:new String[]{owner.email(),"unknown@example.invalid"})
            assertEquals(429,assertThrows(ApplicationFault.class,()->registration.requestResetCode(email)).httpStatus());
    }
    @Test void globallyUnavailableMailRejectsKnownAndUnknownResetRequestsEqually(){
        var owner=create("owner@example.invalid","TENANT_ADMIN","Reset Space",null,null);
        mail.enabled=false;
        for(String email:new String[]{owner.email(),"unknown@example.invalid"})
            assertEquals(503,assertThrows(ApplicationFault.class,()->registration.requestResetCode(email)).httpStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_email_challenge WHERE purpose='RESET_PASSWORD'",Integer.class));
    }
    private PublicRegistrationService.RegistrationResult create(String email,String role,String workspace,String joinType,String joinValue){
        registration.requestRegistrationCode(email);return registration.register(input(email,mail.code(email,EmailPurpose.REGISTER),role,workspace,joinType,joinValue));
    }
    private static PublicRegistrationService.RegistrationInput input(String email,String code,String role,String space,String joinType,String joinValue){
        return new PublicRegistrationService.RegistrationInput(email,code,PASSWORD,PASSWORD,"Display name",role,space,joinType,joinValue);
    }
    private static class FakeMail implements EmailCodeSender {
        final Map<String,String> values=new ConcurrentHashMap<>();
        boolean enabled=true,fail;int deliveries;Runnable observe=()->{};
        public boolean available(){return enabled;}
        public void send(String email,EmailPurpose purpose,String code){
            deliveries++;observe.run();values.put(email+":"+purpose,code);
            if(fail)throw new IllegalStateException("sensitive-supplier-diagnostic");
        }
        String code(String email,EmailPurpose purpose){return values.get(email+":"+purpose);}
    }
}

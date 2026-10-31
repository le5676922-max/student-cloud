package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMemberPasswordResetTest {
    private static final String PASSWORD="Synthetic-password-123";
    private JdbcTemplate jdbc;
    private AccountService accounts;
    private AccountAdministration administration;
    private PublicRegistrationService registration;
    private WorkspaceEnrollmentService enrollment;
    private EmailCodeService codes;
    private FakeMail mail;
    private Actor global,owner;
    private DataSourceTransactionManager manager;
    private final Clock clock=Clock.systemUTC();

    @BeforeEach void setup(){
        var database=new DriverManagerDataSource("jdbc:h2:mem:managed_reset_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-email-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql"),new ClassPathResource("db/independent-public-profile-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);manager=new DataSourceTransactionManager(database);
        mail=new FakeMail();codes=new EmailCodeService(jdbc,manager,clock,mail,"synthetic-internal-key-12345678901234567890");
        enrollment=new WorkspaceEnrollmentService(jdbc,manager,clock);
        registration=new PublicRegistrationService(jdbc,manager,clock,codes,enrollment,true,true);
        administration=new AccountAdministration(jdbc,manager,clock,enrollment::assertMemberActivationAllowed);
        accounts=new AccountService(jdbc,new TransactionTemplate(manager),clock,Duration.ofHours(24));
        accounts.install("Synthetic platform","root-admin",PASSWORD);
        global=accounts.authenticate(accounts.login("root-admin",PASSWORD).token());
        owner=owner("first");
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}

    @Test void legacyPasswordAssignmentCannotChangePasswordOrSessions(){
        var member=member("legacy@example.invalid");
        var old=accounts.login(member.email(),PASSWORD);
        String hash=hash(member.id());long version=version(member.id());
        assertEquals(410,assertThrows(ApplicationFault.class,()->administration.resetMemberPassword(owner,member.id(),"Administrator-chosen-password-123")).httpStatus());
        assertEquals(hash,hash(member.id()));assertEquals(version,version(member.id()));
        assertEquals(member.id(),accounts.authenticate(old.token()).id());
    }

    @Test void managedEmailDoesNotChangePasswordUntilTheMemberConsumesItsProof(){
        var member=member("managed@example.invalid");var old=accounts.login(member.email(),PASSWORD);
        String hash=hash(member.id());long version=version(member.id());
        send(owner,member.id());
        assertEquals(hash,hash(member.id()));assertEquals(version,version(member.id()));
        assertEquals(member.id(),accounts.authenticate(old.token()).id());
        String code=mail.code(member.email());assertNotNull(code);
        registration.resetPassword(member.email(),code,"Self-chosen-password-456","Self-chosen-password-456");
        assertThrows(ApplicationFault.class,()->accounts.authenticate(old.token()));
        assertThrows(ApplicationFault.class,()->accounts.login(member.email(),PASSWORD));
        assertEquals(member.id(),accounts.login(member.email(),"Self-chosen-password-456").account().id());
        assertEquals(version+1,version(member.id()));
        assertThrows(ApplicationFault.class,()->registration.resetPassword(member.email(),code,PASSWORD,PASSWORD));
    }

    @Test void sameMailboxSharesCooldownAndDailyBudgetWithPublicForgotPassword(){
        var member=member("budget@example.invalid");
        send(owner,member.id());
        assertEquals(429,assertThrows(ApplicationFault.class,()->send(owner,member.id())).httpStatus());
        assertEquals(429,assertThrows(ApplicationFault.class,()->registration.requestResetCode(member.email())).httpStatus());
        jdbc.update("UPDATE sc_email_challenge SET next_send_at=0,sent_count=10 WHERE email=? AND purpose='RESET_PASSWORD'",member.email());
        assertEquals(429,assertThrows(ApplicationFault.class,()->send(owner,member.id())).httpStatus());
        assertEquals(1,mail.deliveries);
    }

    @Test void onlyCurrentOrdinaryAdministratorMayTargetAUserInTheirOwnSpace(){
        var member=member("scope@example.invalid");Actor another=owner("second");
        assertEquals(404,assertThrows(ApplicationFault.class,()->send(another,member.id())).httpStatus());
        assertEquals(403,assertThrows(ApplicationFault.class,()->send(global,member.id())).httpStatus());
        Actor user=accounts.authenticate(accounts.login(member.email(),PASSWORD).token());
        assertEquals(403,assertThrows(ApplicationFault.class,()->send(user,member.id())).httpStatus());
        assertEquals(404,assertThrows(ApplicationFault.class,()->send(owner,owner.id())).httpStatus());
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?",owner.id());
        assertEquals(401,assertThrows(ApplicationFault.class,()->send(owner,member.id())).httpStatus());
        assertEquals(0,mail.deliveries);
    }

    @Test void missingMailboxOrUnavailableSmtpNeverFallsBackToPasswordAssignment(){
        var noMailbox=member(null);
        assertEquals(400,assertThrows(ApplicationFault.class,()->send(owner,noMailbox.id())).httpStatus());
        var member=member("no-smtp@example.invalid");String hash=hash(member.id());
        mail.enabled=false;
        assertEquals(503,assertThrows(ApplicationFault.class,()->send(owner,member.id())).httpStatus());
        assertEquals(hash,hash(member.id()));assertEquals(0,mail.deliveries);
        mail.enabled=true;
        var resetDisabled=new PublicRegistrationService(jdbc,manager,clock,codes,enrollment,true,false);
        assertEquals(503,assertThrows(ApplicationFault.class,()->administration.sendMemberPasswordResetEmail(owner,member.id(),resetDisabled::requestManagedResetCode)).httpStatus());
    }

    @Test void authorizedDeliveryFailureIsReportedWithoutProviderDetailsOrSessionRevocation(){
        var member=member("failure@example.invalid");var old=accounts.login(member.email(),PASSWORD);
        String hash=hash(member.id());long version=version(member.id());mail.fail=true;
        var failure=assertThrows(ApplicationFault.class,()->send(owner,member.id()));
        assertEquals(503,failure.httpStatus());assertFalse(failure.getMessage().contains("provider-diagnostic"));
        assertEquals(hash,hash(member.id()));assertEquals(version,version(member.id()));
        assertEquals(member.id(),accounts.authenticate(old.token()).id());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM sc_email_challenge WHERE email=? AND purpose='RESET_PASSWORD'",String.class,member.email()));
        assertEquals(429,assertThrows(ApplicationFault.class,()->send(owner,member.id())).httpStatus());
    }

    @Test void passwordResetNeverApprovesPendingMemberOrReenablesSuspendedMember(){
        var pending=member("pending@example.invalid");
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?",pending.id());
        jdbc.update("INSERT INTO sc_workspace_join_request(workspace_id,account_id,status,created_at) VALUES(?,?,'PENDING',?)",owner.workspaceId(),pending.id(),clock.millis());
        send(owner,pending.id());
        registration.resetPassword(pending.email(),mail.code(pending.email()),"Member-choice-password-123","Member-choice-password-123");
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=?",Boolean.class,pending.id()));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM sc_workspace_join_request WHERE account_id=?",String.class,pending.id()));
        assertThrows(ApplicationFault.class,()->accounts.login(pending.email(),"Member-choice-password-123"));
        var disabled=member("disabled@example.invalid");administration.setMemberEnabled(owner,disabled.id(),false);
        send(owner,disabled.id());registration.resetPassword(disabled.email(),mail.code(disabled.email()),"Member-choice-password-123","Member-choice-password-123");
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=?",Boolean.class,disabled.id()));
    }

    @Test void legacyDirectMemberCreationIsRetiredWithoutInsertingAnyAccount(){
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM sc_account",Integer.class);
        assertEquals(410,assertThrows(ApplicationFault.class,()->administration.rejectDirectMemberCreation(owner)).httpStatus());
        assertEquals(403,assertThrows(ApplicationFault.class,()->administration.rejectDirectMemberCreation(global)).httpStatus());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM sc_account",Integer.class));
    }

    private void send(Actor caller,long memberId){administration.sendMemberPasswordResetEmail(caller,memberId,registration::requestManagedResetCode);}

    private Actor owner(String name){
        var created=administration.createWorkspaceAdministrator(global,"owner-"+name,name+"@owner.invalid",PASSWORD,"Synthetic "+name);
        return accounts.authenticate(accounts.login(created.accountId(),PASSWORD).token());
    }
    private AccountView member(String email){return administration.createMember(owner,"member-"+UUID.randomUUID().toString().substring(0,8),email,PASSWORD);}
    private String hash(long id){return jdbc.queryForObject("SELECT password_hash FROM sc_account WHERE id=?",String.class,id);}
    private long version(long id){return jdbc.queryForObject("SELECT session_version FROM sc_account WHERE id=?",Long.class,id);}
    private static final class FakeMail implements EmailCodeSender {
        boolean enabled=true,fail;
        int deliveries;
        final Map<String,String> values=new HashMap<>();
        public boolean available(){return enabled;}
        public void send(String email,EmailPurpose purpose,String code){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"SMTP must not hold the account database transaction");
            deliveries++;
            if(fail)throw new IllegalStateException("private-provider-diagnostic");
            values.put(email+":"+purpose,code);
        }
        String code(String email){return values.get(email+":"+EmailPurpose.RESET_PASSWORD);}
    }
}

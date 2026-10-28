package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceEnrollmentServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WorkspaceEnrollmentService service;
    private MutableClock clock;
    private Actor owner;
    private Actor other;

    @BeforeEach void setup() {
        var database = new DriverManagerDataSource("jdbc:h2:mem:enrollment_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);
        var manager=new DataSourceTransactionManager(database);
        transaction=new TransactionTemplate(manager);
        clock=new MutableClock();
        service=new WorkspaceEnrollmentService(jdbc,manager,clock);
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'First space',TRUE,1),(2,'Second space',TRUE,1)");
        account(1,Role.TENANT_ADMIN,1L,true);
        account(2,Role.TENANT_ADMIN,2L,true);
        account(3,Role.ADMIN,null,true);
        owner=actor(1,Role.TENANT_ADMIN,1L);
        other=actor(2,Role.TENANT_ADMIN,2L);
    }

    @Test void createsMultipleInvitationsAndReturnsRawCodeOnlyOnce() {
        var first=service.createInvitation(owner,null,null);
        var second=service.createInvitation(owner,7,2);
        assertNotEquals(first.id(),second.id());
        assertTrue(first.code().matches("[a-f0-9]{32}"));
        assertNull(first.expiresAt());
        assertNull(first.maxUses());
        assertEquals(2,service.listInvitations(owner).size());
        assertTrue(service.listInvitations(other).isEmpty());
        String stored=jdbc.queryForObject("SELECT code_hash FROM sc_workspace_invitation WHERE id=?",String.class,Long.parseLong(first.id()));
        assertNotEquals(first.code(),stored);
        assertEquals(64,stored.length());
        assertFalse(java.util.Arrays.stream(WorkspaceEnrollmentService.InvitationView.class.getRecordComponents()).anyMatch(component->component.getName().equals("code")));
        assertEquals(403,assertThrows(ApplicationFault.class,()->service.createInvitation(actor(3,Role.ADMIN,null),null,null)).httpStatus());
        assertEquals(404,assertThrows(ApplicationFault.class,()->service.revokeInvitation(other,Long.parseLong(first.id()))).httpStatus());
    }

    @Test void inviteEnablesMemberAndCountCannotBeConsumedTwiceOrPastLimit() {
        var invitation=service.createInvitation(owner,1,1);
        account(10,Role.USER,null,false);
        account(11,Role.USER,null,false);
        var joined=transaction.execute(status->service.enrollMember("INVITE",invitation.code(),10));
        assertNotNull(joined);
        assertFalse(joined.pending());
        assertEquals(1,joined.workspaceId());
        assertTrue(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=10",Boolean.class));
        assertEquals(1,jdbc.queryForObject("SELECT use_count FROM sc_workspace_invitation WHERE id=?",Integer.class,Long.parseLong(invitation.id())));
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.enrollMember("INVITE",invitation.code(),10)));
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.enrollMember("INVITE",invitation.code(),11)));
        assertNull(jdbc.queryForObject("SELECT workspace_id FROM sc_account WHERE id=11",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT use_count FROM sc_workspace_invitation WHERE id=?",Integer.class,Long.parseLong(invitation.id())));
    }

    @Test void revokeExpirationAndDisabledInviterNeverGrantMembership() {
        var revoked=service.createInvitation(owner,null,null);
        service.revokeInvitation(owner,Long.parseLong(revoked.id()));
        account(10,Role.USER,null,false);
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.enrollMember("INVITE",revoked.code(),10)));
        var timed=service.createInvitation(owner,1,null);
        clock.advance(86_400_001L);
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.enrollMember("INVITE",timed.code(),10)));
        var disabled=service.createInvitation(owner,null,null);
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=1");
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.enrollMember("INVITE",disabled.code(),10)));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=10",Boolean.class));
    }

    @Test void nameJoinRequiresReviewAndCannotBypassUsingEnableEndpoint() {
        account(10,Role.USER,null,false);
        var joined=transaction.execute(status->service.enrollMember("NAME","First space",10));
        assertTrue(joined.pending());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=10",Boolean.class));
        var request=service.joinRequests(owner).getFirst();
        assertTrue(service.joinRequests(other).isEmpty());
        assertEquals(409,assertThrows(ApplicationFault.class,()->transaction.executeWithoutResult(status->service.assertMemberActivationAllowed(owner,10))).httpStatus());
        assertEquals(404,assertThrows(ApplicationFault.class,()->service.reviewJoinRequest(other,Long.parseLong(request.id()),true)).httpStatus());
        service.reviewJoinRequest(owner,Long.parseLong(request.id()),true);
        service.reviewJoinRequest(owner,Long.parseLong(request.id()),true);
        assertTrue(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=10",Boolean.class));
        transaction.executeWithoutResult(status->service.assertMemberActivationAllowed(owner,10));
        assertTrue(service.joinRequests(owner).isEmpty());
        assertEquals(409,assertThrows(ApplicationFault.class,()->service.reviewJoinRequest(owner,Long.parseLong(request.id()),false)).httpStatus());
    }

    @Test void rejectionRemainsDisabledAndCannotBeReactivatedWithoutApproval() {
        account(10,Role.USER,null,false);
        transaction.execute(status->service.enrollMember("NAME","First space",10));
        var request=service.joinRequests(owner).getFirst();
        service.reviewJoinRequest(owner,Long.parseLong(request.id()),false);
        service.reviewJoinRequest(owner,Long.parseLong(request.id()),false);
        assertFalse(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=10",Boolean.class));
        assertEquals(409,assertThrows(ApplicationFault.class,()->transaction.executeWithoutResult(status->service.assertMemberActivationAllowed(owner,10))).httpStatus());
    }

    @Test void newAdministratorNeedsNoReviewAndEnrollmentRequiresOuterTransaction() {
        account(10,Role.TENANT_ADMIN,null,false);
        assertThrows(IllegalStateException.class,()->service.newWorkspace(10,"New space"));
        assertThrows(IllegalStateException.class,()->service.enrollMember("NAME","First space",10));
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->{service.newWorkspace(10,"Rolled back");throw new IllegalStateException("rollback fixture");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace WHERE name='Rolled back'",Integer.class));
        assertNull(jdbc.queryForObject("SELECT workspace_id FROM sc_account WHERE id=10",Long.class));
        var created=transaction.execute(status->service.newWorkspace(10,"New space"));
        assertFalse(created.pending());
        assertTrue(jdbc.queryForObject("SELECT enabled FROM sc_account WHERE id=10",Boolean.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace_join_request WHERE account_id=10",Integer.class));
        account(11,Role.TENANT_ADMIN,null,false);
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.newWorkspace(11,"new space")));
        jdbc.update("INSERT INTO sc_workspace(name,enabled,created_at) VALUES('First space',TRUE,1)");
        account(12,Role.USER,null,false);
        assertThrows(ApplicationFault.class,()->transaction.execute(status->service.enrollMember("NAME","First space",12)));
    }

    @Test void racingFinalInvitationSeatHasOnlyOneWinner() throws Exception {
        var invitation=service.createInvitation(owner,1,1);
        account(10,Role.USER,null,false);
        account(11,Role.USER,null,false);
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->joinRace(barrier,invitation.code(),10));
            var second=executor.submit(()->joinRace(barrier,invitation.code(),11));
            int winners=(first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0);
            assertEquals(1,winners);
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE id IN(10,11) AND workspace_id=1 AND enabled=TRUE",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT use_count FROM sc_workspace_invitation WHERE id=?",Integer.class,Long.parseLong(invitation.id())));
    }

    private boolean joinRace(CyclicBarrier barrier,String code,long id) throws Exception {
        barrier.await(5,TimeUnit.SECONDS);
        try {transaction.execute(status->service.enrollMember("INVITE",code,id));return true;}
        catch(ApplicationFault rejected) {return false;}
    }
    private void account(long id,Role role,Long workspace,boolean enabled) {
        jdbc.update("INSERT INTO sc_account(id,account_id,username,email,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,?,?,?,?,?,1,1)",
                id,"SCFIXTURE"+id,"fixture-"+id,"fixture-"+id+"@example.invalid","not-used",role.name(),workspace,enabled);
    }
    private Actor actor(long id,Role role,Long workspace) {return new Actor(id,"SCFIXTURE"+id,"fixture-"+id,"fixture-"+id+"@example.invalid",role,workspace);}
    private static final class MutableClock extends Clock {
        private final AtomicLong millis=new AtomicLong(Instant.parse("2026-10-26T00:00:00Z").toEpochMilli());
        void advance(long amount){millis.addAndGet(amount);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(millis.get());}
        @Override public long millis(){return millis.get();}
    }
}

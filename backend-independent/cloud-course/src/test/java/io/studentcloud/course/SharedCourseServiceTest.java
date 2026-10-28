package io.studentcloud.course;

import io.studentcloud.account.Actor;
import io.studentcloud.account.JdbcAccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class SharedCourseServiceTest {
    private static final long QUOTA = 100L * 1024 * 1024;
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private SharedCourseService courses;
    private Actor owner;
    private Actor secondAdmin;
    private Actor member;
    private Actor otherMember;

    @BeforeEach
    void isolatedDatabase() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:student_cloud_independent_courses_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"), new ClassPathResource("db/independent-courses-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        courses = new SharedCourseService(jdbc, new DataSourceTransactionManager(source), clock, new JdbcAccountDirectory(jdbc));
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'Synthetic first space',TRUE,?),(2,'Synthetic second space',TRUE,?)", clock.millis(), clock.millis());
        owner = account(1, Role.TENANT_ADMIN, 1L);
        secondAdmin = account(2, Role.TENANT_ADMIN, 2L);
        member = account(3, Role.USER, 2L);
        otherMember = account(4, Role.USER, 2L);
    }

    @AfterEach
    void closeGeneratedDatabaseOnly() { jdbc.execute("SHUTDOWN"); }

    @Test
    void onlyAnActualWorkspaceAdministratorMayCreateACourse() {
        assertThrows(ApplicationFault.class, () -> courses.create(member, "Course", "", QUOTA, true));
        Actor forged = new Actor(member.id(), "forged", "forged", null, Role.TENANT_ADMIN, 1L);
        assertThrows(ApplicationFault.class, () -> courses.create(forged, "Course", "", QUOTA, true));
        CourseView created = create();
        assertTrue(created.creator());
        assertEquals(0, created.memberCount());
        assertEquals(0, created.materialCount());
        assertEquals(0, created.usedBytes());
    }

    @Test
    void usersAndOtherAdministratorsJoinWithoutChangingTheirWorkspaceOrPrivateCloudGrants() {
        CourseView course = create();
        CreatedInvitation invitation = courses.createInvitation(owner, course.id(), null, null);
        assertFalse(courses.previewInvitation(member, invitation.code()).joined());
        assertEquals(course.id(), courses.join(member, invitation.code()).id());
        assertEquals(course.id(), courses.join(secondAdmin, invitation.code()).id());
        assertEquals(2, courses.get(owner, course.id()).memberCount());
        assertEquals(2L, jdbc.queryForObject("SELECT workspace_id FROM sc_account WHERE id=3", Long.class));
        assertEquals(1, courses.list(member).size());
        assertThrows(ApplicationFault.class, () -> courses.members(member, course.id()));
    }

    @Test
    void independentInvitationsExposeTheRawSecretOnlyAtCreation() {
        CourseView course = create();
        CreatedInvitation first = courses.createInvitation(owner, course.id(), null, null);
        CreatedInvitation second = courses.createInvitation(owner, course.id(), clock.millis() + 60_000, 2);
        assertNotEquals(first.code(), second.code());
        assertTrue(first.code().matches("[0-9a-f]{32}"));
        List<String> hashes = jdbc.queryForList("SELECT code_hash FROM sc_course_invitation", String.class);
        assertTrue(hashes.stream().allMatch(value -> value.matches("[0-9a-f]{64}")));
        assertFalse(hashes.contains(first.code()));
        List<InviteView> list = courses.invitations(owner, course.id(), 0, 20);
        assertEquals(2, list.size());
        assertFalse(list.toString().contains(first.code()));
        assertNull(list.get(1).expiresAt());
        assertThrows(ApplicationFault.class, () -> courses.invitations(secondAdmin, course.id(), 0, 20));
    }

    @Test
    void uploadsRequireAnExplicitApplicationAndApprovalFromThisCourseCreator() {
        CourseView course = create();
        String code = courses.createInvitation(owner, course.id(), null, null).code();
        courses.join(member, code);
        assertThrows(ApplicationFault.class, () -> courses.requireUpload(member, course.id()));
        assertThrows(ApplicationFault.class, () -> courses.reviewUpload(owner, course.id(), member.id(), true));
        courses.requestUpload(member, course.id());
        assertEquals("PENDING", courses.get(member, course.id()).uploadStatus());
        assertThrows(ApplicationFault.class, () -> courses.reviewUpload(secondAdmin, course.id(), member.id(), true));
        courses.reviewUpload(owner, course.id(), member.id(), true);
        assertEquals("APPROVED", courses.requireUpload(member, course.id()).uploadStatus());
        courses.requestUpload(member, course.id());
        assertEquals("APPROVED", courses.requireUpload(member, course.id()).uploadStatus());
        courses.reviewUpload(owner, course.id(), member.id(), false);
        assertThrows(ApplicationFault.class, () -> courses.requireUpload(member, course.id()));
        assertNotNull(courses.requireRead(member, course.id()));
    }

    @Test
    void leavingRetainsHistoricalSeatAndRejoiningClearsUploadApproval() {
        CourseView course = create();
        CreatedInvitation invite = courses.createInvitation(owner, course.id(), null, 1);
        courses.join(member, invite.code());
        courses.requestUpload(member, course.id());
        courses.reviewUpload(owner, course.id(), member.id(), true);
        courses.leave(member, course.id());
        assertThrows(ApplicationFault.class, () -> courses.join(otherMember, invite.code()));
        courses.join(member, invite.code());
        assertEquals("NONE", courses.get(member, course.id()).uploadStatus());
        courses.join(member, invite.code());
        assertEquals(1, courses.invitations(owner, course.id(), 0, 20).getFirst().usedCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_invitation_use", Integer.class));
    }

    @Test
    void removedMemberCannotUseAnotherInvitationToBypassTheCreatorsDecision() {
        CourseView course = create();
        String first = courses.createInvitation(owner, course.id(), null, null).code();
        String second = courses.createInvitation(owner, course.id(), null, null).code();
        courses.join(member, first);
        courses.removeMember(owner, course.id(), member.id());
        assertThrows(ApplicationFault.class, () -> courses.join(member, first));
        assertThrows(ApplicationFault.class, () -> courses.join(member, second));
        assertThrows(ApplicationFault.class, () -> courses.previewInvitation(member, second));
        assertThrows(ApplicationFault.class, () -> courses.requireRead(member, course.id()));
        assertTrue(courses.list(member).isEmpty());
    }

    @Test
    void concurrentClaimsForTheFinalInvitationSeatHaveExactlyOneWinner() throws Exception {
        CourseView course = create();
        CreatedInvitation invitation = courses.createInvitation(owner, course.id(), null, 1);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = List.of(member, otherMember).stream().map(actor -> pool.submit(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var separate = new SharedCourseService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), clock, new JdbcAccountDirectory(jdbc));
                    separate.join(actor, invitation.code());
                    successes.incrementAndGet();
                } catch (ApplicationFault expected) {
                    assertEquals(409, expected.httpStatus());
                    conflicts.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            })).toList();
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            assertEquals(1, successes.get());
            assertEquals(1, conflicts.get());
            assertEquals(1, courses.get(owner, course.id()).memberCount());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_invitation_use", Integer.class));
            assertEquals(1, courses.invitations(owner, course.id(), 0, 20).getFirst().usedCount());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void expiredRevokedAndPausedInvitationsCannotCreateMemberships() {
        CourseView course = create();
        assertThrows(ApplicationFault.class, () -> courses.createInvitation(owner, course.id(), clock.millis(), null));
        assertThrows(ApplicationFault.class, () -> courses.createInvitation(owner, course.id(), null, 0));
        CreatedInvitation revoked = courses.createInvitation(owner, course.id(), null, null);
        courses.revokeInvitation(owner, course.id(), revoked.id());
        courses.revokeInvitation(owner, course.id(), revoked.id());
        assertThrows(ApplicationFault.class, () -> courses.join(member, revoked.code()));
        CreatedInvitation expired = courses.createInvitation(owner, course.id(), clock.millis() + 100, null);
        jdbc.update("UPDATE sc_course_invitation SET expires_at=? WHERE id=?", clock.millis(), expired.id());
        assertThrows(ApplicationFault.class, () -> courses.join(member, expired.code()));
        CreatedInvitation paused = courses.createInvitation(owner, course.id(), null, null);
        courses.update(owner, course.id(), course.name(), "", QUOTA, false);
        assertThrows(ApplicationFault.class, () -> courses.join(member, paused.code()));
        assertEquals(0, courses.get(owner, course.id()).memberCount());
    }

    @Test
    void courseSettingsAndRemovalAreCreatorScopedAndDoNotPretendToDeleteMaterialFiles() {
        CourseView course = create();
        assertThrows(ApplicationFault.class, () -> courses.update(secondAdmin, course.id(), "Other", "", QUOTA, true));
        CourseView updated = courses.update(owner, course.id(), "Updated course", "Updated description", QUOTA, true);
        assertEquals("Updated course", updated.name());
        jdbc.update("UPDATE sc_shared_course SET used_bytes=1,material_count=1 WHERE id=?", course.id());
        assertThrows(ApplicationFault.class, () -> courses.delete(owner, course.id()));
        jdbc.update("UPDATE sc_shared_course SET used_bytes=0,material_count=0 WHERE id=?", course.id());
        courses.delete(owner, course.id());
        assertTrue(courses.list(owner).isEmpty());
        assertThrows(ApplicationFault.class, () -> courses.get(owner, course.id()));
    }

    @Test
    void disabledAccountsAndWorkspacesLoseAccessOnTheNextOperation() {
        CourseView course = create();
        String code = courses.createInvitation(owner, course.id(), null, null).code();
        courses.join(member, code);
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?", member.id());
        assertThrows(ApplicationFault.class, () -> courses.requireRead(member, course.id()));
        jdbc.update("UPDATE sc_workspace SET enabled=FALSE WHERE id=?", owner.workspaceId());
        assertThrows(ApplicationFault.class, () -> courses.update(owner, course.id(), "Changed", "", QUOTA, true));
    }

    private CourseView create() { return courses.create(owner, "Synthetic course", "Shared synthetic materials", QUOTA, true); }

    private Actor account(long id, Role role, Long workspaceId) {
        String publicId = "SC" + String.format("%032d", id);
        jdbc.update("INSERT INTO sc_account(id,account_id,username,email,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,?,?,?,?,TRUE,1,?)",
                id, publicId, "synthetic-user-" + id, "synthetic-" + id + "@example.invalid", "not-used-for-login", role.name(), workspaceId, clock.millis());
        return new Actor(id, publicId, "synthetic-user-" + id, "synthetic-" + id + "@example.invalid", role, workspaceId);
    }
}

package im.tlgxy.cloud.module.course;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JDBC transactions, temporary course objects and synthetic accounts; no live database or network. */
class SharedCourseServiceTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private SharedCourseService service;
    private CourseFileStore files;
    private final Map<Integer, User> actors = new HashMap<>();

    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("courses.db"));
        source.setDriverClassName("org.sqlite.JDBC");
        var options = new Properties(); options.setProperty("foreign_keys", "true"); options.setProperty("busy_timeout", "10000"); source.setConnectionProperties(options);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE `user`(id INTEGER PRIMARY KEY,username TEXT,nickname TEXT)");
        for (User user : List.of(user(10, UserRoleEnum.TENANT_ADMIN, 9), user(11, UserRoleEnum.USER, 9),
                user(20, UserRoleEnum.TENANT_ADMIN, 8), user(21, UserRoleEnum.USER, 8), user(1, UserRoleEnum.ADMIN, 1))) {
            actors.put(user.getId(), user);
            jdbc.update("INSERT INTO `user` VALUES(?,?,?)", user.getId(), user.getUsername(), user.getNickname());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V79__shared_courses.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V80__shared_course_invitations.sql")).execute(source);
        UserService users = mock(UserService.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            User user = actors.get(call.getArgument(0));
            if (user == null || !Boolean.TRUE.equals(user.getEnable())) throw new BizException("账号不可用");
            return user;
        });
        files = spy(new CourseFileStore(directory.resolve("course-files").toString()));
        service = new SharedCourseService(jdbc, users, files, new DataSourceTransactionManager(source), 1024);
    }

    @Test void crossWorkspaceMembersAndAdministratorsJoinWithoutReceivingPrivateWorkspaceOrUploadAccess() throws Exception {
        var course = create(10, 100);
        assertEquals(32, course.joinCode().length());
        assertNotEquals(course.joinCode(), jdbc.queryForObject("SELECT code_hash FROM shared_course", String.class));
        assertThrows(BizException.class, () -> service.materials(21, course.course().id()));
        service.join(21, course.joinCode()); service.join(20, course.joinCode());
        assertEquals(8, actors.get(21).getTenantId());
        assertEquals(2, service.get(10, course.course().id()).memberCount());
        var material = service.upload(10, course.course().id(), "讲义.txt", input("hello"));
        assertEquals(1, service.materials(21, course.course().id()).size());
        try (var content = service.download(20, course.course().id(), material.id()).resource().getInputStream()) {
            assertEquals("hello", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (int member : List.of(20, 21)) {
            assertThrows(BizException.class, () -> service.upload(member, course.course().id(), "wrong.txt", input("no")));
            assertThrows(BizException.class, () -> service.members(member, course.course().id()));
            assertThrows(BizException.class, () -> service.rotateCode(member, course.course().id()));
            assertThrows(BizException.class, () -> service.deleteMaterial(member, course.course().id(), material.id()));
        }
        // No personal-grant table exists: course operations have not attempted to grant or enumerate private clouds.
        assertEquals(1, service.list(20).size()); assertFalse(service.list(20).getFirst().creator());
    }

    @Test void onlyTheCreatorApprovesOneTimeUploadRequestsAndRevocationIsImmediate() {
        var course = create(10, 100); String id = course.course().id();
        service.join(20, course.joinCode());
        assertThrows(BizException.class, () -> service.reviewUpload(10, id, 20, true));
        service.requestUpload(20, id); service.requestUpload(20, id);
        assertEquals("PENDING", service.members(10, id).getFirst().uploadStatus());
        assertThrows(BizException.class, () -> service.reviewUpload(20, id, 20, true));
        service.reviewUpload(10, id, 20, true);
        service.upload(20, id, "one.txt", input("one"));
        service.upload(20, id, "two.txt", input("two"));
        service.reviewUpload(10, id, 20, false);
        assertThrows(BizException.class, () -> service.upload(20, id, "three.txt", input("three")));
        assertEquals(2, service.materials(20, id).size());
        service.leave(20, id);
        assertFalse(service.canRead(20, id));
        assertThrows(BizException.class, () -> service.download(20, id, service.materials(10, id).getFirst().id()));
        assertEquals("NONE", service.join(20, course.joinCode()).uploadStatus());
        service.removeMember(10, id, 20);
        assertFalse(service.canRead(20, id));
        assertThrows(BizException.class, () -> service.join(20, course.joinCode()));
    }

    @Test void disabledCreatorOrCourseAndRotatedCodesNeverLeaveAStaleAccessGrant() {
        var course = create(10, 100); String id = course.course().id();
        service.join(21, course.joinCode());
        String newCode = service.rotateCode(10, id);
        assertThrows(BizException.class, () -> service.join(11, course.joinCode()));
        service.join(11, newCode);
        service.edit(10, id, new SharedCourseService.Draft("Paused", "", 100), false);
        assertFalse(service.canRead(21, id));
        assertThrows(BizException.class, () -> service.join(20, newCode));
        service.edit(10, id, new SharedCourseService.Draft("Active", "", 100), true);
        actors.get(10).setRole(UserRoleEnum.USER);
        assertFalse(service.canRead(21, id));
        assertThrows(BizException.class, () -> service.requestUpload(21, id));
    }

    @Test void concurrentUploadsAtomicallyReserveCapacityAndNeverExposeTheRejectedObject() throws Exception {
        var course = create(10, 30); String id = course.course().id();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(n -> executor.submit(() -> {
                try { service.upload(10, id, "part-" + n + ".txt", input("12345678901234567890")); return true; }
                catch (BizException denied) { return false; }
            })).toList();
            int successes = 0; for (var job : jobs) if (job.get()) successes++;
            assertEquals(1, successes);
        }
        assertEquals(20, service.get(10, id).usedBytes());
        assertEquals(1, service.materials(10, id).size());
        assertThrows(BizException.class, () -> service.edit(10, id, new SharedCourseService.Draft("Too small", "", 19), true));
        String material = service.materials(10, id).getFirst().id();
        service.deleteMaterial(10, id, material);
        assertEquals(0, service.get(10, id).usedBytes());
        assertEquals(0, service.materials(10, id).size());
    }

    @Test void failedFileCommitRollsBackCapacityAndCleanupFailureRetainsConservativeAccounting() {
        var course = create(10, 100); String id = course.course().id();
        doThrow(new BizException("synthetic write failure")).when(files).commit(any(), eq(id), anyString());
        assertThrows(BizException.class, () -> service.upload(10, id, "failed.txt", input("one")));
        assertEquals(0, service.get(10, id).usedBytes());
        assertEquals(0, service.incompleteUploads(10, id).size());
        doCallRealMethod().when(files).commit(any(), eq(id), anyString());
        var stored = service.upload(10, id, "kept.txt", input("keep"));
        doThrow(new BizException("synthetic delete failure")).when(files).delete(id, stored.id());
        assertThrows(BizException.class, () -> service.deleteMaterial(10, id, stored.id()));
        assertEquals(4, service.get(10, id).usedBytes());
        assertEquals(1, service.materials(10, id).size());
        doCallRealMethod().when(files).delete(id, stored.id());
        service.deleteMaterial(10, id, stored.id());
        assertEquals(0, service.get(10, id).usedBytes());
    }

    @Test void filenamesIdsAndRolesCannotBypassTheCourseObjectBoundary() throws Exception {
        var course = create(10, 100); String id = course.course().id();
        assertThrows(BizException.class, () -> create(11, 100));
        assertThrows(BizException.class, () -> create(1, 100));
        assertThrows(BizException.class, () -> service.join(21, "../private"));
        for (String filename : List.of("../secret", "c:\\secret", "line\r\nHeader", ".", ""))
            assertThrows(BizException.class, () -> service.upload(10, id, filename, input("x")));
        assertThrows(BizException.class, () -> service.upload(10, id, "empty", input("")));
        var own = service.upload(10, id, "normal.txt", input("x"));
        var other = create(20, 100);
        assertThrows(BizException.class, () -> service.download(20, other.course().id(), own.id()));
        assertFalse(Files.exists(directory.resolve("secret")));
        try (var staging = Files.list(directory.resolve("course-files"))) {
            assertTrue(staging.noneMatch(p -> p.toString().endsWith(".part")));
        }
        actors.get(21).setEnable(false);
        assertThrows(BizException.class, () -> service.join(21, course.joinCode()));
    }

    @Test void invitationPreviewIsMinimalAndDoesNotJoinOrGrantAccessUntilConfirmation() throws Exception {
        var created = create(10, 100); String id = created.course().id();
        var invite = service.createInvitation(10, id);
        assertEquals(32, invite.code().length());
        assertNull(invite.expiresAt());
        assertNotEquals(invite.code(), jdbc.queryForObject("SELECT code_hash FROM shared_course_invitation WHERE id=?", String.class, invite.id()));
        var preview = service.previewInvitation(21, invite.code().toUpperCase(java.util.Locale.ROOT));
        assertEquals(id, preview.id()); assertEquals("Data structures", preview.name());
        assertFalse(preview.joined()); assertFalse(preview.creator()); assertEquals(invite.expiresAt(), preview.expiresAt());
        assertEquals(0, service.get(10, id).memberCount());
        assertThrows(BizException.class, () -> service.materials(21, id));
        var projection = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(service.invitations(10, id).getFirst());
        assertFalse(projection.has("code")); assertFalse(projection.has("codeHash"));
        assertEquals(6, projection.size());
        var material = service.upload(10, id, "course.txt", input("shared"));
        for (int actor : List.of(21, 20)) {
            assertEquals("NONE", service.join(actor, invite.code()).uploadStatus());
            assertEquals(1, service.materials(actor, id).size());
            try (var content = service.download(actor, id, material.id()).resource().getInputStream()) {
                assertEquals("shared", new String(content.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(8, actors.get(actor).getTenantId());
            assertThrows(BizException.class, () -> service.upload(actor, id, "blocked.txt", input("x")));
        }
        assertEquals(2, service.invitations(10, id).getFirst().usedCount());
    }

    @Test void invitationJoinsAreIdempotentAndDoNotResetApprovedUploadOrCountOwner() {
        var created = create(10, 100); String id = created.course().id();
        var invite = service.createInvitation(10, id);
        assertTrue(service.previewInvitation(10, invite.code()).creator());
        assertTrue(service.previewInvitation(10, invite.code()).joined());
        service.join(10, invite.code());
        assertEquals(0, service.invitations(10, id).getFirst().usedCount());
        service.join(21, invite.code()); service.join(21, invite.code());
        service.requestUpload(21, id); service.reviewUpload(10, id, 21, true);
        assertEquals("APPROVED", service.join(21, invite.code()).uploadStatus());
        assertEquals(1, service.invitations(10, id).getFirst().usedCount());
        assertTrue(service.previewInvitation(21, invite.code()).joined());
        service.leave(21, id);
        assertEquals("NONE", service.join(21, invite.code()).uploadStatus());
        assertEquals(1, service.invitations(10, id).getFirst().usedCount());
        assertEquals(1, service.get(10, id).memberCount());
    }

    @Test void revocationAndExpiryRejectPreviewAndNewJoinsWithoutRemovingExistingMembers() {
        var created = create(10, 100); String id = created.course().id();
        var revoked = service.createInvitation(10, id);
        service.join(21, revoked.code());
        service.revokeInvitation(10, id, revoked.id());
        long revokedAt = service.invitations(10, id).getFirst().revokedAt();
        assertTrue(revokedAt > 0);
        service.revokeInvitation(10, id, revoked.id());
        assertEquals(revokedAt, service.invitations(10, id).getFirst().revokedAt());
        assertThrows(BizException.class, () -> service.previewInvitation(20, revoked.code()));
        assertThrows(BizException.class, () -> service.join(20, revoked.code()));
        assertTrue(service.canRead(21, id));
        var expired = service.createInvitation(10, id);
        jdbc.update("UPDATE shared_course_invitation SET expires_at=? WHERE id=?", System.currentTimeMillis() - 1, expired.id());
        assertThrows(BizException.class, () -> service.previewInvitation(20, expired.code()));
        assertThrows(BizException.class, () -> service.join(20, expired.code()));
        assertEquals(1, service.get(10, id).memberCount());
        assertEquals(0, jdbc.queryForObject("SELECT used_count FROM shared_course_invitation WHERE id=?", Long.class, expired.id()));
    }

    @Test void independentInvitationsCoexistAndRemainValidWhenLegacyCourseCodeChanges() {
        var created = create(10, 100); String id = created.course().id();
        var first = service.createInvitation(10, id); var second = service.createInvitation(10, id);
        String replacement = service.rotateCode(10, id);
        assertThrows(BizException.class, () -> service.previewInvitation(21, created.joinCode()));
        assertThrows(BizException.class, () -> service.join(21, created.joinCode()));
        assertNull(service.previewInvitation(20, replacement).expiresAt());
        service.join(11, first.code()); service.join(21, second.code()); service.join(20, replacement);
        assertEquals(3, service.get(10, id).memberCount());
        assertTrue(service.invitations(10, id).stream().allMatch(invite -> invite.usedCount() == 1));
    }

    @Test void onlyCreatorCanIssueListOrRevokeAndInvitationIdsAreBoundToTheirCourse() {
        var course = create(10, 100); String id = course.course().id();
        var invite = service.createInvitation(10, id);
        service.join(20, invite.code());
        for (int outsider : List.of(20, 21, 1)) {
            assertThrows(BizException.class, () -> service.createInvitation(outsider, id));
            assertThrows(BizException.class, () -> service.invitations(outsider, id));
            assertThrows(BizException.class, () -> service.revokeInvitation(outsider, id, invite.id()));
        }
        var other = create(20, 100);
        assertThrows(BizException.class, () -> service.revokeInvitation(20, other.course().id(), invite.id()));
        assertThrows(BizException.class, () -> service.revokeInvitation(10, id, "../secret"));
        assertEquals(0, service.invitations(10, id).getFirst().revokedAt());
        assertThrows(BizException.class, () -> service.previewInvitation(1, invite.code()));
        assertThrows(BizException.class, () -> service.previewInvitation(21, "bad-code"));
        assertThrows(BizException.class, () -> service.previewInvitation(21, "0".repeat(32)));
    }

    @Test void removedDisabledAndOutOfScopeAccountsCannotUseFreshInvitations() {
        var created = create(10, 100); String id = created.course().id();
        var invite = service.createInvitation(10, id);
        service.join(21, invite.code()); service.removeMember(10, id, 21);
        var fresh = service.createInvitation(10, id);
        assertThrows(BizException.class, () -> service.previewInvitation(21, fresh.code()));
        assertThrows(BizException.class, () -> service.join(21, fresh.code()));
        service.edit(10, id, new SharedCourseService.Draft("Paused", "", 100), false);
        assertThrows(BizException.class, () -> service.createInvitation(10, id));
        assertThrows(BizException.class, () -> service.previewInvitation(20, fresh.code()));
        assertThrows(BizException.class, () -> service.join(20, fresh.code()));
        service.edit(10, id, new SharedCourseService.Draft("Active", "", 100), true);
        actors.get(20).setEnable(false);
        assertThrows(BizException.class, () -> service.join(20, fresh.code()));
        actors.get(10).setTenantId(99);
        assertThrows(BizException.class, () -> service.previewInvitation(11, fresh.code()));
        assertThrows(BizException.class, () -> service.join(11, fresh.code()));
        assertThrows(BizException.class, () -> service.createInvitation(10, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM shared_course_member WHERE course_id=? AND status='JOINED'", Long.class, id));
    }

    @Test void invitationCodesCanBeCreatedRepeatedlyAndListsHaveStablePagination() {
        var created = create(10, 100); String id = created.course().id();
        for (int index = 0; index < 25; index++) service.createInvitation(10, id);
        jdbc.update("UPDATE shared_course_invitation SET created_at=123 WHERE course_id=?", id);
        var first = service.invitations(10, id, 0, 20);
        var second = service.invitations(10, id, 20, 20);
        assertEquals(20, first.size()); assertEquals(5, second.size());
        var ids = new java.util.ArrayList<String>();
        first.forEach(invitation -> ids.add(invitation.id())); second.forEach(invitation -> ids.add(invitation.id()));
        assertEquals(25, new java.util.HashSet<>(ids).size());
        var sorted = ids.stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertEquals(sorted, ids);
        assertEquals(first, service.invitations(10, id));
        assertTrue(service.invitations(10, id, 25, 20).isEmpty());
        assertThrows(BizException.class, () -> service.invitations(10, id, -1, 20));
        assertThrows(BizException.class, () -> service.invitations(10, id, 0, 0));
        assertThrows(BizException.class, () -> service.invitations(10, id, 0, 101));
        service.createInvitation(10, id);
        assertEquals(26, service.invitations(10, id, 0, 100).size());
    }

    @Test void customExpiryIsOptionalAndMustBeAFutureReasonableTimestamp() {
        var created = create(10, 100); String id = created.course().id();
        long expiry = System.currentTimeMillis() + 3600_000;
        var invite = service.createInvitation(10, id, expiry);
        assertEquals(expiry, invite.expiresAt());
        assertEquals(expiry, service.previewInvitation(21, invite.code()).expiresAt());
        assertEquals(expiry, service.invitations(10, id).getFirst().expiresAt());
        for (long invalid : List.of(-1L, 0L, System.currentTimeMillis() - 1, Long.MAX_VALUE))
            assertThrows(BizException.class, () -> service.createInvitation(10, id, invalid));
        assertEquals(1, service.invitations(10, id).size());
        assertNull(service.createInvitation(10, id, null).expiresAt());
    }

    @Test void invitationLimitsCountDistinctMembersAndAllowPreviouslyCountedMembersToRejoin() {
        var created = create(10, 100); String id = created.course().id();
        var invite = service.createInvitation(10, id, null, 1);
        assertNull(invite.expiresAt()); assertEquals(1, invite.maxMembers());
        service.join(10, invite.code());
        service.join(21, invite.code()); service.join(21, invite.code());
        assertEquals(1, service.invitations(10, id).getFirst().usedCount());
        assertEquals(1, service.invitations(10, id).getFirst().maxMembers());
        assertTrue(service.previewInvitation(21, invite.code()).joined());
        assertThrows(BizException.class, () -> service.previewInvitation(11, invite.code()));
        assertThrows(BizException.class, () -> service.join(11, invite.code()));
        service.leave(21, id);
        assertFalse(service.previewInvitation(21, invite.code()).joined());
        assertEquals("NONE", service.join(21, invite.code()).uploadStatus());
        assertEquals(1, service.invitations(10, id).getFirst().usedCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shared_course_invitation_use WHERE invitation_id=?", Long.class, invite.id()));
        var another = service.createInvitation(10, id, null, 2);
        service.join(11, another.code());
        assertEquals(2, service.get(10, id).memberCount());
        service.removeMember(10, id, 21);
        assertThrows(BizException.class, () -> service.join(21, invite.code()));
        assertThrows(BizException.class, () -> service.join(21, another.code()));
        assertEquals(1, jdbc.queryForObject("SELECT used_count FROM shared_course_invitation WHERE id=?", Long.class, invite.id()));
        assertEquals(1, jdbc.queryForObject("SELECT used_count FROM shared_course_invitation WHERE id=?", Long.class, another.id()));
    }

    @Test void alreadyJoinedMembersDoNotConsumeAnotherInvitationsRemainingPlaces() {
        var created = create(10, 100); String id = created.course().id();
        service.join(21, created.joinCode());
        var invite = service.createInvitation(10, id, null, 1);
        service.join(21, invite.code());
        assertEquals(0, service.invitations(10, id).getFirst().usedCount());
        service.join(11, invite.code());
        assertTrue(service.previewInvitation(21, invite.code()).joined());
        service.join(21, invite.code());
        assertThrows(BizException.class, () -> service.join(20, invite.code()));
        assertEquals(1, service.invitations(10, id).getFirst().usedCount());
        for (int invalid : List.of(-1, 0, 501))
            assertThrows(BizException.class, () -> service.createInvitation(10, id, null, invalid));
        assertNull(service.createInvitation(10, id).maxMembers());
    }

    @Test void concurrentJoinersCannotBothSpendTheLastInvitationPlace() throws Exception {
        var created = create(10, 100); String id = created.course().id();
        var invite = service.createInvitation(10, id, null, 1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var jobs = List.of(11, 21).stream().map(actor -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                try { service.join(actor, invite.code()); return true; }
                catch (BizException denied) { return false; }
            })).toList();
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (var job : jobs) if (job.get(15, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally { start.countDown(); }
        assertEquals(1, service.get(10, id).memberCount());
        assertEquals(1, service.invitations(10, id).getFirst().usedCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shared_course_invitation_use WHERE invitation_id=?", Long.class, invite.id()));
    }

    private SharedCourseService.Created create(int owner, long quota) { return service.create(owner, new SharedCourseService.Draft("Data structures", "Synthetic course", quota)); }
    private static ByteArrayInputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static User user(int id, UserRoleEnum role, int tenant) {
        User user = new User(); user.setId(id); user.setRole(role); user.setTenantId(tenant); user.setEnable(true);
        user.setUsername("synthetic-" + id); user.setNickname("Course user " + id); return user;
    }
}

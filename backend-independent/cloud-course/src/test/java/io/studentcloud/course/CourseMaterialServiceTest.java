package io.studentcloud.course;

import io.studentcloud.account.Actor;
import io.studentcloud.account.JdbcAccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

class CourseMaterialServiceTest {
    @TempDir Path root;
    private JdbcTemplate jdbc;
    private SharedCourseService courses;
    private CourseMaterialService materials;
    private DataSourceTransactionManager transactions;
    private final Clock clock = Clock.systemUTC();
    private final Actor owner = new Actor(1, "synthetic-owner", "owner", null, Role.TENANT_ADMIN, 1L);
    private final Actor member = new Actor(2, "synthetic-member", "member", null, Role.USER, 1L);
    private long courseId;

    @BeforeEach
    void databaseAndPrivateFiles() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:student_cloud_independent_materials_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"), new ClassPathResource("db/independent-courses-schema.sql"),
                new ClassPathResource("db/independent-course-materials-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        courses = new SharedCourseService(jdbc, transactions, clock, new JdbcAccountDirectory(jdbc));
        materials = new CourseMaterialService(jdbc, transactions, clock, courses, root);
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'Synthetic space',TRUE,?)", clock.millis());
        for (Actor actor : new Actor[]{owner, member})
            jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,'not-for-login',?,1,TRUE,1,?)",
                    actor.id(), actor.accountId(), actor.username(), actor.role().name(), clock.millis());
        courseId = courses.create(owner, "Synthetic materials", "", 1024 * 1024, true).id();
    }

    @AfterEach
    void closeGeneratedDatabaseOnly() { jdbc.execute("SHUTDOWN"); }

    @Test
    void actualUploadDownloadAndDeletePersistOnlyRandomPrivateObjectNames() throws Exception {
        MaterialView uploaded = materials.upload(owner, courseId, "讲义.md", input("hello"), 5);
        assertEquals("READY", uploaded.status());
        assertEquals(1, materials.list(owner, courseId).size());
        assertEquals(5, courses.get(owner, courseId).usedBytes());
        try (MaterialDownload download = materials.download(owner, courseId, uploaded.id())) {
            assertEquals("hello", new String(download.content().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("讲义.md", download.material().filename());
        }
        try (var files = Files.walk(root)) {
            var names = files.filter(Files::isRegularFile).map(file -> file.getFileName().toString()).toList();
            assertEquals(1, names.size());
            assertTrue(names.getFirst().matches("[0-9a-f]{32}\\.bin"));
        }
        materials.delete(owner, courseId, uploaded.id());
        materials.delete(owner, courseId, uploaded.id());
        assertTrue(materials.list(owner, courseId).isEmpty());
        assertEquals(0, courses.get(owner, courseId).usedBytes());
        assertEquals(0, courses.get(owner, courseId).materialCount());
        assertThrows(ApplicationFault.class, () -> materials.download(owner, courseId, uploaded.id()));
    }

    @Test
    void membersReadSharedMaterialsButNeedTheCreatorsApprovalForUploadsAndCannotDelete() throws Exception {
        String invitation = courses.createInvitation(owner, courseId, null, null).code();
        courses.join(member, invitation);
        MaterialView existing = materials.upload(owner, courseId, "shared.txt", input("hello"), 5);
        assertEquals(1, materials.list(member, courseId).size());
        assertThrows(ApplicationFault.class, () -> materials.upload(member, courseId, "member.txt", input("x"), 1));
        assertThrows(ApplicationFault.class, () -> materials.delete(member, courseId, existing.id()));
        courses.requestUpload(member, courseId);
        courses.reviewUpload(owner, courseId, member.id(), true);
        assertEquals("READY", materials.upload(member, courseId, "member.txt", input("x"), 1).status());
        courses.removeMember(owner, courseId, member.id());
        assertThrows(ApplicationFault.class, () -> materials.download(member, courseId, existing.id()));
    }

    @Test
    void failedExactLengthValidationReleasesOnlyItsOwnReservationAndDoesNotPublishAMaterial() throws Exception {
        assertThrows(IOException.class, () -> materials.upload(owner, courseId, "broken.txt", input("x"), 2));
        assertEquals(0, courses.get(owner, courseId).usedBytes());
        assertTrue(materials.list(owner, courseId).isEmpty());
        assertTrue(materials.incomplete(owner, courseId).isEmpty());
        try (var files = Files.walk(root)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }

    @Test
    void quotaAndConfigurationFailuresDoNotReadTheUploadStreamOrCreateMetadata() {
        assertThrows(ApplicationFault.class, () -> materials.upload(owner, courseId, "too-large.txt", input("x"), 1024 * 1024 + 1));
        CourseMaterialService unconfigured = new CourseMaterialService(jdbc, transactions, clock, courses, null);
        ApplicationFault missing = assertThrows(ApplicationFault.class, () -> unconfigured.upload(owner, courseId, "x.txt", input("x"), 1));
        assertEquals(503, missing.httpStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_material", Integer.class));
    }

    @Test
    void slowInputRunsOutsideTransactionsAndRevokedUploadPermissionCannotPublish() {
        courses.join(member, courses.createInvitation(owner, courseId, null, null).code());
        courses.requestUpload(member, courseId);
        courses.reviewUpload(owner, courseId, member.id(), true);
        var input = new ByteArrayInputStream(new byte[]{1}) {
            private boolean revoked;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Network reads must not hold SQL locks");
                if (!revoked) {
                    revoked = true;
                    courses.reviewUpload(owner, courseId, member.id(), false);
                }
                return super.read(bytes, offset, length);
            }
        };
        assertThrows(ApplicationFault.class, () -> materials.upload(member, courseId, "revoked.txt", input, 1));
        assertTrue(assertDoesNotThrow(() -> materials.list(owner, courseId)).isEmpty());
        assertEquals(0, courses.get(owner, courseId).usedBytes());
    }

    @Test
    void activeUploadReservationCannotBePrematurelyRemovedToBypassQuota() {
        jdbc.update("INSERT INTO sc_course_material(course_id,filename,object_key,size_bytes,state,created_at,updated_at) VALUES(?,?,?,?,'PENDING',?,?)",
                courseId, "pending.txt", "0123456789abcdef0123456789abcdef.bin", 100, clock.millis(), clock.millis());
        jdbc.update("UPDATE sc_shared_course SET used_bytes=100 WHERE id=?", courseId);
        Long id = jdbc.queryForObject("SELECT id FROM sc_course_material", Long.class);
        assertThrows(ApplicationFault.class, () -> materials.delete(owner, courseId, id));
        assertEquals(100, courses.get(owner, courseId).usedBytes());
    }

    private static ByteArrayInputStream input(String content) { return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)); }
}

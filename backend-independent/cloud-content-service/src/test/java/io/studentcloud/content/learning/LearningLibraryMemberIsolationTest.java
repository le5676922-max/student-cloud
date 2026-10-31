package io.studentcloud.content.learning;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.content.http.InternalCourseAccessController;
import io.studentcloud.course.SharedCourseService;
import io.studentcloud.drive.application.MountService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

/** Same-role members, real H2 owner indexes, and independently granted synthetic local files. */
class LearningLibraryMemberIsolationTest {
    private static final String QUERY = "isolationcheck";
    private static final Actor ADMIN = new Actor(1, "root", "root", null, Role.ADMIN, null);
    private static final Actor ALICE = member(3, 7);
    private static final Actor BOB = member(4, 7);
    private static final Actor EVE = member(5, 8);
    @TempDir Path temporary;
    private final Map<Long, Actor> enabled = new ConcurrentHashMap<>();
    private final Map<Long, Path> sourceFiles = new ConcurrentHashMap<>();
    private final Map<Long, LearningLibraryService.Course> courses = new ConcurrentHashMap<>();
    private final Map<Long, LearningLibraryService.Material> materials = new ConcurrentHashMap<>();
    private JdbcTemplate jdbc;
    private LearningLibraryService library;
    private SharedCourseService sharedCourses;
    private InternalCourseAccessController courseAccess;

    @BeforeEach void setup() throws Exception {
        enabled.putAll(Map.of(1L, ADMIN, 3L, ALICE, 4L, BOB, 5L, EVE));
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:library_member_isolation_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),
                new ClassPathResource("db/independent-learning-schema.sql"),
                new ClassPathResource("db/independent-courses-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) {
                Actor actor = enabled.get(id);
                if (actor == null) throw new ApplicationFault("42000", 401, "Synthetic account disabled");
                return actor;
            }
            public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
        };
        var tx = new DataSourceTransactionManager(database);
        sharedCourses = new SharedCourseService(jdbc, tx, Clock.systemUTC(), accounts);
        courseAccess = new InternalCourseAccessController(sharedCourses, accounts);
        Path managed = Files.createDirectory(temporary.resolve("managed"));
        var mounts = new MountService(jdbc, tx, Clock.systemUTC(), managed, accounts);
        Path seven = Files.createDirectory(managed.resolve("seven"));
        Path eight = Files.createDirectory(managed.resolve("eight"));
        var mountSeven = mounts.createLocal(ADMIN, "Workspace seven", "drive-seven", seven, 7L);
        var mountEight = mounts.createLocal(ADMIN, "Workspace eight", "drive-eight", eight, 8L);
        library = new LearningLibraryService(jdbc, tx, Clock.systemUTC(), accounts, mounts);
        for (Actor actor : List.of(ALICE, BOB, EVE)) {
            Path mountRoot = actor.workspaceId() == 7 ? seven : eight;
            Path directory = Files.createDirectories(mountRoot.resolve("members/u" + actor.id()));
            Path source = directory.resolve("notes.txt");
            Files.writeString(source, QUERY + " " + marker(actor));
            sourceFiles.put(actor.id(), source);
            long mountId = actor.workspaceId() == 7 ? mountSeven.id() : mountEight.id();
            jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(?,?,?,'list,read',TRUE,1,1)",
                    actor.id(), mountId, "/members/u" + actor.id());
            var course = library.createCourse(actor, "Same course name", "Autumn");
            courses.put(actor.id(), course);
            materials.put(actor.id(), library.addMaterial(actor, course.id(), storageKey(actor), "/notes.txt", QUERY));
        }
    }

    @AfterEach void shutdown() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @Test void switchingMembersKeepsCoursesMaterialIdsAndIndexedTextStrictlyPerAccount() {
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(DISTINCT owner_id) FROM sc_learning_course", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sc_learning_material", Integer.class));
        for (Actor actor : List.of(ALICE, BOB, EVE, ALICE, BOB)) {
            assertEquals(List.of(courses.get(actor.id())), library.listCourses(actor));
            assertEquals(List.of(materials.get(actor.id())), library.listMaterials(actor, courses.get(actor.id()).id()));
            assertOwnCitation(actor, library.search(actor, null, QUERY, 8));
            assertOwnCitation(actor, library.search(actor, courses.get(actor.id()).id(), QUERY, 8));
        }
    }

    @Test void guessingAnotherMembersCourseOrMaterialIdsCannotReadOrMutateTheirPersonalIndex() {
        var aliceCourse = courses.get(ALICE.id());
        var aliceMaterial = materials.get(ALICE.id());
        for (Actor caller : List.of(BOB, EVE, ADMIN)) {
            assertNotFound(() -> library.listMaterials(caller, aliceCourse.id()));
            assertNotFound(() -> library.search(caller, aliceCourse.id(), QUERY, 8));
            assertNotFound(() -> library.updateCourse(caller, aliceCourse.id(), "stolen", ""));
            assertNotFound(() -> library.deleteCourse(caller, aliceCourse.id()));
            assertNotFound(() -> library.addMaterial(caller, aliceCourse.id(), "drive-seven", "/notes.txt", ""));
            assertNotFound(() -> library.refresh(caller, aliceMaterial.id()));
            assertNotFound(() -> library.relink(caller, aliceMaterial.id(), "drive-seven", "/notes.txt"));
            assertNotFound(() -> library.deleteMaterial(caller, aliceMaterial.id()));
        }
        assertEquals(List.of(aliceCourse), library.listCourses(ALICE));
        assertOwnCitation(ALICE, library.search(ALICE, null, QUERY, 8));
        assertOwnCitation(BOB, library.search(BOB, null, QUERY, 8));
        assertOwnCitation(EVE, library.search(EVE, null, QUERY, 8));
    }

    @Test void revokedReadPermissionAndChangedSourceCannotLeakAnExistingIndexWhilePeersRemainReadable() throws Exception {
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE,revision=revision+1 WHERE user_id=?", ALICE.id());
        assertTrue(jdbc.queryForObject("SELECT indexed_text FROM sc_learning_material WHERE id=?", String.class,
                materials.get(ALICE.id()).id()).contains(marker(ALICE)), "The old index still exists for this permission test");
        var revoked = library.search(ALICE, null, QUERY, 8);
        assertTrue(revoked.citations().isEmpty());
        assertEquals(1, revoked.skipped());
        assertOwnCitation(BOB, library.search(BOB, null, QUERY, 8));
        assertOwnCitation(EVE, library.search(EVE, null, QUERY, 8));
        assertThrows(ApplicationFault.class, () -> library.refresh(ALICE, materials.get(ALICE.id()).id()));

        jdbc.update("UPDATE sc_drive_grant SET enabled=TRUE,revision=revision+1 WHERE user_id=?", ALICE.id());
        assertOwnCitation(ALICE, library.search(ALICE, null, QUERY, 8));
        Files.writeString(sourceFiles.get(ALICE.id()), QUERY + " ALICE_REPLACED_SOURCE");
        var changed = library.search(ALICE, null, QUERY, 8);
        assertTrue(changed.citations().isEmpty());
        assertEquals(1, changed.skipped());
        library.refresh(ALICE, materials.get(ALICE.id()).id());
        var refreshed = library.search(ALICE, null, QUERY, 8);
        assertEquals(1, refreshed.citations().size());
        assertTrue(refreshed.citations().getFirst().excerpt().contains("ALICE_REPLACED_SOURCE"));
        assertFalse(refreshed.citations().getFirst().excerpt().contains(marker(ALICE)));
        assertOwnCitation(BOB, library.search(BOB, null, QUERY, 8));
    }

    @Test void staleClientWorkspaceCannotReadAnOldGrantAndDisabledAccountsCannotQueryAnyIndex() {
        enabled.put(ALICE.id(), member(ALICE.id(), 8));
        var moved = library.search(ALICE, null, QUERY, 8);
        assertTrue(moved.citations().isEmpty(), "Source authorization must use fresh server membership");
        assertEquals(1, moved.skipped());
        assertNotFound(() -> library.search(ALICE, courses.get(EVE.id()).id(), QUERY, 8));
        assertThrows(ApplicationFault.class, () -> library.refresh(ALICE, materials.get(ALICE.id()).id()));
        assertOwnCitation(BOB, library.search(BOB, null, QUERY, 8));
        assertOwnCitation(EVE, library.search(EVE, null, QUERY, 8));

        enabled.remove(ALICE.id());
        assertEquals(401, assertThrows(ApplicationFault.class, () -> library.listCourses(ALICE)).httpStatus());
        assertEquals(401, assertThrows(ApplicationFault.class, () -> library.search(ALICE, null, QUERY, 8)).httpStatus());
        assertOwnCitation(BOB, library.search(BOB, null, QUERY, 8));
    }

    @Test void courseKnowledgeAuthorizationUsesRealActiveMembershipAcrossWorkspaceAndRevocationChanges() {
        Actor creator = new Actor(2, "teacher", "teacher", null, Role.TENANT_ADMIN, 7L);
        enabled.put(creator.id(), creator);
        var course = sharedCourses.create(creator, "Shared course", "", 1024 * 1024, true);
        String invite = sharedCourses.createInvitation(creator, course.id(), null, null).code();
        assertEquals(403, assertThrows(ApplicationFault.class, () -> courseAccess.read(course.id(), ALICE.id())).httpStatus());
        assertEquals(403, assertThrows(ApplicationFault.class, () -> courseAccess.read(course.id(), EVE.id())).httpStatus());

        sharedCourses.join(ALICE, invite);
        sharedCourses.join(EVE, invite);
        assertEquals("0", courseAccess.read(course.id(), ALICE.id()).code());
        assertEquals("0", courseAccess.read(course.id(), EVE.id()).code(), "A real invited member may belong to another workspace");
        assertEquals(403, assertThrows(ApplicationFault.class, () -> courseAccess.read(course.id(), BOB.id())).httpStatus(),
                "Same role and workspace do not grant course membership");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_member WHERE course_id=? AND state='ACTIVE'", Integer.class, course.id()));

        sharedCourses.leave(ALICE, course.id());
        assertEquals("LEFT", jdbc.queryForObject("SELECT state FROM sc_course_member WHERE course_id=? AND account_id=?", String.class, course.id(), ALICE.id()));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> courseAccess.read(course.id(), ALICE.id())).httpStatus());
        sharedCourses.removeMember(creator, course.id(), EVE.id());
        assertEquals("REMOVED", jdbc.queryForObject("SELECT state FROM sc_course_member WHERE course_id=? AND account_id=?", String.class, course.id(), EVE.id()));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> courseAccess.read(course.id(), EVE.id())).httpStatus());

        sharedCourses.join(BOB, invite);
        assertEquals("0", courseAccess.read(course.id(), BOB.id()).code());
        enabled.remove(BOB.id());
        assertEquals(401, assertThrows(ApplicationFault.class, () -> courseAccess.read(course.id(), BOB.id())).httpStatus());
    }

    private void assertOwnCitation(Actor owner, LearningLibraryService.SearchResult result) {
        assertEquals(1, result.citations().size());
        assertEquals(0, result.skipped());
        var citation = result.citations().getFirst();
        assertEquals(materials.get(owner.id()).id(), citation.materialId());
        assertEquals(courses.get(owner.id()).id(), citation.courseId());
        assertEquals(storageKey(owner), citation.storageKey());
        assertEquals("/notes.txt", citation.path());
        assertTrue(citation.excerpt().contains(marker(owner)));
        for (Actor peer : List.of(ALICE, BOB, EVE))
            if (peer.id() != owner.id()) assertFalse(citation.excerpt().contains(marker(peer)));
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable action) {
        assertEquals(404, assertThrows(ApplicationFault.class, action).httpStatus());
    }
    private static String marker(Actor actor) {
        return actor.id() == ALICE.id() ? "ALICE_PRIVATE_INDEX" : actor.id() == BOB.id() ? "BOB_PRIVATE_INDEX" : "EVE_PRIVATE_INDEX";
    }
    private static String storageKey(Actor actor) { return actor.workspaceId() == 7 ? "drive-seven" : "drive-eight"; }
    private static Actor member(long id, long workspace) {
        return new Actor(id, "account-" + id, "member-" + id, null, Role.USER, workspace);
    }
}

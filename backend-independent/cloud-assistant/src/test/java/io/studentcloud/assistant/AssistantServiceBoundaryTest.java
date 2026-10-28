package io.studentcloud.assistant;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.*;

/** The intelligence database deliberately has no identity, workspace or course tables. */
class AssistantServiceBoundaryTest {
    private final Actor admin = new Actor(1, "SC_ADMIN", "admin", null, Role.ADMIN, null);
    private final Actor member = new Actor(2, "SC_MEMBER", "member", null, Role.USER, 7L);
    private final Actor other = new Actor(3, "SC_OTHER", "other", null, Role.USER, 8L);
    private final Map<Long, Actor> enabled = new ConcurrentHashMap<>();
    private final Set<Long> readers = ConcurrentHashMap.newKeySet();
    private JdbcTemplate jdbc;
    private MemoryService memory;
    private AnswerFeedbackService feedback;
    private ReviewedKnowledgeService knowledge;

    @BeforeEach void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:intelligence_boundary_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/independent-assistant-schema.sql"));
        }
        enabled.put(admin.id(), admin); enabled.put(member.id(), member); enabled.put(other.id(), other);
        AccountDirectory directory = new AccountDirectory() {
            @Override public Actor account(long id) {
                Actor actor = enabled.get(id);
                if (actor == null) throw new ApplicationFault("42000", 401, "账号不可用");
                return actor;
            }
            @Override public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
        };
        CourseKnowledgeAccess courses = (actor, courseId) -> {
            if (courseId != 12 || !readers.contains(actor.id())) throw new ApplicationFault("40300", 403, "课程不可访问");
        };
        var tx = new DataSourceTransactionManager(dataSource);
        memory = new MemoryService(jdbc, tx, Clock.systemUTC(), directory);
        feedback = new AnswerFeedbackService(jdbc, tx, Clock.systemUTC(), directory, courses);
        knowledge = new ReviewedKnowledgeService(jdbc, tx, Clock.systemUTC(), directory, courses);
    }

    @Test void memoryUsesOnlyLocalGuardAndRemoteIdentityPort() {
        memory.create(member, "Keep answers concise.");
        assertEquals(1, memory.list(member).size());
        assertTrue(memory.list(other).isEmpty());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM sc_assistant_owner_guard WHERE account_id=?", Long.class, member.id()));
        enabled.remove(member.id());
        assertEquals(401, assertThrows(ApplicationFault.class, () -> memory.list(member)).httpStatus());
    }

    @Test void reviewAndPublicationRemainLocalWithoutIdentityTables() {
        var receipt = feedback.submit(member, "Where is upload?", "Unverified answer", "question-one");
        var reviewed = feedback.review(admin, receipt.id(), 1, "Use the upload action.", "Internal review", FeedbackStatus.RESOLVED);
        var publication = knowledge.publishReviewed(admin, receipt.id(), reviewed.version(), 0, reviewed.updatedAt(),
                "Upload help", reviewed.correctedAnswer(), KnowledgeScope.TENANT, false);
        assertEquals(1, publication.version());
        assertEquals(1, knowledge.search(member, "Upload", 5).size());
        assertTrue(knowledge.search(other, "Upload", 5).isEmpty());
        assertEquals(409, assertThrows(ApplicationFault.class,
                () -> feedback.review(admin, receipt.id(), 1, "A stale edit", FeedbackStatus.RESOLVED)).httpStatus());
        assertEquals(403, assertThrows(ApplicationFault.class, () -> feedback.list(other, null, 0, 20)).httpStatus());
    }

    @Test void courseKnowledgeChecksRemoteMembershipAndCannotBecomeGlobal() {
        readers.add(member.id());
        var receipt = feedback.submit(member, "Course guide", "Unverified course answer", "course-question", 12L);
        var reviewed = feedback.review(admin, receipt.id(), 1, "Reviewed course guide", FeedbackStatus.RESOLVED);
        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> knowledge.publish(admin, receipt.id(), reviewed.version(), "Course guide", KnowledgeScope.GLOBAL, null, true)).httpStatus());
        knowledge.publishReviewed(admin, receipt.id(), reviewed.version(), 0, reviewed.updatedAt(), "Course guide", reviewed.correctedAnswer(), KnowledgeScope.COURSE, false);
        assertTrue(knowledge.search(member, "guide", 5).isEmpty());
        assertEquals(1, knowledge.search(member, "guide", 5, 12L).size());
        assertTrue(feedback.exportReviewed(admin).isEmpty());
        readers.remove(member.id());
        assertEquals(403, assertThrows(ApplicationFault.class, () -> knowledge.search(member, "guide", 5, 12L)).httpStatus());
    }

    @Test void changingCourseContextCannotReuseAnExistingFeedbackKey() {
        readers.add(member.id());
        feedback.submit(member, "Question", "Answer", "one-key");
        assertEquals(409, assertThrows(ApplicationFault.class,
                () -> feedback.submit(member, "Question", "Answer", "one-key", 12L)).httpStatus());
    }
}

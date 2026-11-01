package io.studentcloud.assistant;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackExportPrivacyTest {
    private final Actor admin = new Actor(1, "SC_ADMIN", "admin", null, Role.ADMIN, null);
    private final Actor user = new Actor(2, "SC_USER", "user", null, Role.USER, 7L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private AccountDirectory accounts;

    @BeforeEach void setUp() {
        var database = new DriverManagerDataSource("jdbc:h2:mem:export_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        tx = new DataSourceTransactionManager(database);
        accounts = new AccountDirectory() {
            public Actor account(long id) { return id == admin.id() ? admin : user; }
            public boolean workspaceEnabled(long id) { return id == 7; }
        };
    }

    @Test void exportWithoutProvenancePolicyCannotReleaseReviewedPrivateOrUnknownAnswers() {
        var feedback = new AnswerFeedbackService(jdbc, tx, Clock.systemUTC(), accounts, CourseKnowledgeAccess.unavailable());
        var receipt = feedback.submit(user, "PRIVATE QUESTION", "private original", "unknown-source");
        feedback.review(admin, receipt.id(), 1, "A clean human correction does not erase private provenance", FeedbackStatus.RESOLVED);
        assertTrue(feedback.exportReviewed(admin).isEmpty(), "Missing provenance rules must fail closed");
    }

    @Test void exportKeepsOnlyResolvedPublicNonCourseCorrectionsAndNoAdministrativeFields() {
        Set<Long> publicSources = new HashSet<>();
        var feedback = service(id -> {
            if (!publicSources.contains(id)) throw new ApplicationFault("KNOWLEDGE_PRIVATE_SOURCE", 403, "private or unknown");
        });
        var publicAnswer = resolved(feedback, "Public question", "Public correction", "public");
        publicSources.add(publicAnswer.id());
        resolved(feedback, "Private question", "Sanitized correction", "private");
        resolved(feedback, "Unknown question", "Unknown correction", "unknown");
        var course = feedback.submit(user, "Course question", "original", "course", 12L);
        feedback.review(admin, course.id(), 1, "Course correction", FeedbackStatus.RESOLVED);
        publicSources.add(course.id());
        var open = feedback.submit(user, "Open question", "original", "open");
        publicSources.add(open.id());

        assertEquals(java.util.List.of(new AnswerFeedbackService.ReviewedAnswer("Public question", "Public correction")), feedback.exportReviewed(admin));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(user)).httpStatus());
        assertEquals(java.util.List.of("question", "answer"), java.util.Arrays.stream(AnswerFeedbackService.ReviewedAnswer.class.getRecordComponents()).map(component -> component.getName()).toList());
    }

    @Test void provenanceInfrastructureAndUnexpectedAuthorizationErrorsAbortExport() {
        var unavailable = new ApplicationFault("AUTHORIZATION_UNAVAILABLE", 503, "authorization unavailable");
        var feedback = service(id -> { throw unavailable; });
        resolved(feedback, "Public-looking question", "Human correction", "one");
        assertSame(unavailable, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(admin)));
        var storageFailure = new IllegalStateException("provenance storage unavailable");
        var broken = service(id -> { throw storageFailure; });
        assertSame(storageFailure, assertThrows(IllegalStateException.class, () -> broken.exportReviewed(admin)));
    }

    @Test void exportRemainsBoundedToFiveHundredRecentReviewCandidates() {
        var feedback = service(id -> {});
        for (int index = 0; index < 501; index++) {
            jdbc.update("INSERT INTO sc_answer_feedback(account_id,workspace_id,message_id,question,answer,corrected_answer,note,status,version,created_at,updated_at) VALUES(2,7,?,?,'original',?,'PRIVATE NOTE','RESOLVED',2,?,?)",
                    "message-" + index, "question-" + index, "correction-" + index, index, index);
        }
        var exported = feedback.exportReviewed(admin);
        assertEquals(500, exported.size());
        assertEquals("question-500", exported.getFirst().question());
        assertEquals("correction-1", exported.getLast().answer());
    }

    private AnswerFeedbackService service(KnowledgeSourcePolicy sources) {
        return new AnswerFeedbackService(jdbc, tx, Clock.systemUTC(), accounts, (actor, courseId) -> {}, sources);
    }

    private FeedbackView resolved(AnswerFeedbackService service, String question, String correction, String messageId) {
        var receipt = service.submit(user, question, "original", messageId);
        return service.review(admin, receipt.id(), 1, correction, "PRIVATE NOTE", FeedbackStatus.RESOLVED);
    }
}

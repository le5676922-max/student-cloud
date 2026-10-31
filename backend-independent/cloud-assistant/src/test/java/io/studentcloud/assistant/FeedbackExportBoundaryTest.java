package io.studentcloud.assistant;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackExportBoundaryTest {
    private final Actor admin = new Actor(1, "SC_ADMIN", "admin", null, Role.ADMIN, null);
    private final Actor member = new Actor(2, "SC_MEMBER", "member", null, Role.USER, 7L);
    private final Map<Long, Actor> enabled = new ConcurrentHashMap<>();
    private final AtomicReference<KnowledgeSourcePolicy> policy = new AtomicReference<>(id -> { });
    private final AtomicInteger checked = new AtomicInteger();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private AccountDirectory accounts;
    private AnswerFeedbackService feedback;

    @BeforeEach void setUp() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:export_boundary_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        // Production MySQL commonly compares TEXT with a case-insensitive collation.
        new JdbcTemplate(db).execute("SET COLLATION ENGLISH STRENGTH PRIMARY");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql")).execute(db);
        jdbc = new JdbcTemplate(db);
        tx = new DataSourceTransactionManager(db);
        enabled.put(admin.id(), admin);
        enabled.put(member.id(), member);
        accounts = new AccountDirectory() {
            public Actor account(long id) {
                Actor actor = enabled.get(id);
                if (actor == null) throw new ApplicationFault("42000", 401, "账号不可用");
                return actor;
            }
            public boolean workspaceEnabled(long id) { return id == 7; }
        };
        feedback = new AnswerFeedbackService(jdbc, tx, Clock.systemUTC(), accounts, (actor, course) -> { }, id -> {
            checked.incrementAndGet();
            policy.get().requirePublishable(id);
        });
    }

    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    @Test void currentReviewedGlobalPublicationExportsOnlyQuestionAndCorrection() {
        FeedbackView source = resolved("global", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());

        assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer(source.question(), source.correctedAnswer())), feedback.exportReviewed(admin));
        assertEquals(List.of("question", "answer"), java.util.Arrays.stream(AnswerFeedbackService.ReviewedAnswer.class.getRecordComponents()).map(c -> c.getName()).toList());
        assertEquals(2, checked.get());
    }

    @Test void resolvedFeedbackWithoutPublicationIsNotSharedTrainingData() {
        resolved("unpublished", null);
        assertTrue(feedback.exportReviewed(admin).isEmpty());
        assertEquals(0, checked.get());
    }

    @Test void tenantAndCoursePublicationsAreExcludedBeforeSourceChecks() {
        FeedbackView tenant = resolved("tenant", null);
        publication(tenant, "TENANT", 1, tenant.version(), tenant.correctedAnswer());
        FeedbackView course = resolved("course", 12L);
        publication(course, "COURSE", 1, course.version(), course.correctedAnswer());

        assertTrue(feedback.exportReviewed(admin).isEmpty());
        assertEquals(0, checked.get());
    }

    @Test void revokedAndSupersededPublicationsCannotBeExported() {
        FeedbackView revoked = resolved("revoked", null);
        long revokedId = publication(revoked, "GLOBAL", 1, revoked.version(), revoked.correctedAnswer());
        jdbc.update("UPDATE sc_reviewed_knowledge SET revoked_at=1 WHERE id=?", revokedId);
        FeedbackView superseded = resolved("superseded", null);
        long supersededId = publication(superseded, "GLOBAL", 1, superseded.version(), superseded.correctedAnswer());
        jdbc.update("UPDATE sc_reviewed_knowledge SET superseded_at=1 WHERE id=?", supersededId);

        assertTrue(feedback.exportReviewed(admin).isEmpty());
        assertEquals(0, checked.get());
    }

    @Test void olderGlobalGenerationCannotReappearAfterLatestTenantGeneration() {
        FeedbackView source = resolved("old-generation", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        // Defensive check for inconsistent legacy lifecycle markers as well as normal supersession.
        publication(source, "TENANT", 2, source.version(), source.correctedAnswer());
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void feedbackVersionChangeRequiresFreshPublicationEvenWhenCorrectionIsIdentical() {
        FeedbackView source = resolved("edited", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        feedback.review(admin, source.id(), source.version(), source.correctedAnswer(), "New review note", FeedbackStatus.RESOLVED);
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void publicationMustContainExactCurrentReviewedAnswer() {
        FeedbackView source = resolved("answer-mismatch", null);
        publication(source, "GLOBAL", 1, source.version(), "Different published answer");
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void caseInsensitiveDatabaseEqualityCannotReplaceExactReviewedAnswerMatch() {
        FeedbackView source = resolved("case-mismatch", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer().toUpperCase(java.util.Locale.ROOT));
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void reopenedFeedbackCannotUseItsPreviousPublication() {
        FeedbackView source = resolved("reopened", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        feedback.review(admin, source.id(), source.version(), source.correctedAnswer(), FeedbackStatus.OPEN);
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void missingSourcePolicyFailsClosedForAnEligiblePublication() {
        FeedbackView source = resolved("missing-policy", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        var unconfigured = new AnswerFeedbackService(jdbc, tx, Clock.systemUTC(), accounts, CourseKnowledgeAccess.unavailable());
        assertEquals(503, assertThrows(ApplicationFault.class, () -> unconfigured.exportReviewed(admin)).httpStatus());
    }

    @Test void deniedSourceSkipsOnlyThatCandidateAndDoesNotExposeItsText() {
        FeedbackView allowed = resolved("allowed", null);
        publication(allowed, "GLOBAL", 1, allowed.version(), allowed.correctedAnswer());
        FeedbackView denied = resolved("private-secret", null);
        publication(denied, "GLOBAL", 1, denied.version(), denied.correctedAnswer());
        policy.set(id -> { if (id == denied.id()) throw new ApplicationFault("KNOWLEDGE_PRIVATE_SOURCE", 403, "Private source"); });

        assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer(allowed.question(), allowed.correctedAnswer())), feedback.exportReviewed(admin));
        assertEquals(3, checked.get());
    }

    @Test void policyInfrastructureFailureFailsWholeExport() {
        FeedbackView source = resolved("outage", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        var outage = new ApplicationFault("SOURCE_UNAVAILABLE", 503, "Source unavailable");
        policy.set(id -> { throw outage; });
        assertSame(outage, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(admin)));
    }

    @Test void unexpectedPolicyFaultFailsWholeExport() {
        FeedbackView source = resolved("unexpected-outage", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        var outage = new IllegalStateException("Unavailable provenance store");
        policy.set(id -> { throw outage; });
        assertSame(outage, assertThrows(IllegalStateException.class, () -> feedback.exportReviewed(admin)));
    }

    @Test void sourceDenialCannotCauseAnUnboundedSearchForMoreRows() {
        for (int i = 0; i < 501; i++) {
            FeedbackView source = resolved("bounded-" + i, null);
            publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        }
        policy.set(id -> { throw new ApplicationFault("KNOWLEDGE_PRIVATE_SOURCE", 403, "Private source"); });
        assertTrue(feedback.exportReviewed(admin).isEmpty());
        assertEquals(500, checked.get());
    }

    @Test void exportRemainsBoundedToFiveHundredReviewedAnswers() {
        for (int i = 0; i < 501; i++) {
            FeedbackView source = resolved("bounded-allowed-" + i, null);
            publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        }
        assertEquals(500, feedback.exportReviewed(admin).size());
        assertEquals(1000, checked.get());
    }

    @Test void currentAccountMustRemainGlobalAdministrator() {
        enabled.put(admin.id(), new Actor(admin.id(), admin.accountId(), admin.username(), null, Role.USER, 7L));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(admin)).httpStatus());
        enabled.remove(admin.id());
        assertEquals(401, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(admin)).httpStatus());
        enabled.put(admin.id(), new Actor(admin.id(), "CHANGED_ACCOUNT", admin.username(), null, Role.ADMIN, null));
        assertEquals(401, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(admin)).httpStatus());
        assertEquals(0, checked.get());
    }

    @Test void administratorRevocationDuringSourceCheckPreventsReturningData() {
        FeedbackView source = resolved("revoked-admin", null);
        publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        policy.set(id -> enabled.put(admin.id(), new Actor(admin.id(), admin.accountId(), admin.username(), null, Role.USER, 7L)));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> feedback.exportReviewed(admin)).httpStatus());
    }

    @Test void publicationWithdrawalDuringSourceChecksPreventsReturningData() {
        FeedbackView source = resolved("withdrawn-during-export", null);
        long knowledgeId = publication(source, "GLOBAL", 1, source.version(), source.correctedAnswer());
        policy.set(id -> jdbc.update("UPDATE sc_reviewed_knowledge SET revoked_at=1 WHERE id=?", knowledgeId));
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    private FeedbackView resolved(String key, Long courseId) {
        var receipt = feedback.submit(member, "Question " + key, "Original model answer " + key, key, courseId);
        return feedback.review(admin, receipt.id(), 1, "Reviewed answer " + key, "Private administration note", FeedbackStatus.RESOLVED);
    }

    private long publication(FeedbackView source, String scope, long generation, long sourceVersion, String answer) {
        return AssistantAccess.insertId(jdbc,
                "INSERT INTO sc_reviewed_knowledge(feedback_id,title,answer,scope,workspace_id,course_id,version,source_feedback_version,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                source.id(), "Reviewed publication", answer, scope, scope.equals("TENANT") ? 7L : null,
                scope.equals("COURSE") ? source.courseId() : null, generation, sourceVersion, source.updatedAt());
    }
}

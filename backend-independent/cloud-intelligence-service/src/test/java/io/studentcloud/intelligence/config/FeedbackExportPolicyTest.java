package io.studentcloud.intelligence.config;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.assistant.AnswerFeedbackService;
import io.studentcloud.assistant.CourseKnowledgeAccess;
import io.studentcloud.assistant.FeedbackStatus;
import io.studentcloud.assistant.FeedbackView;
import io.studentcloud.intelligence.ai.ChatAnswerStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the production composition root and persisted answer provenance, without a remote service. */
class FeedbackExportPolicyTest {
    private final Actor admin = new Actor(1, "SC_ADMIN", "admin", null, Role.ADMIN, null);
    private final Actor member = new Actor(2, "SC_MEMBER", "member", null, Role.USER, 7L);
    private JdbcTemplate jdbc;
    private AnswerFeedbackService feedback;
    private ChatAnswerStore answers;
    private final AtomicReference<Runnable> afterExportRead = new AtomicReference<>(() -> { });

    @BeforeEach void setUp() {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:feedback_export_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                new ClassPathResource("db/independent-chat-schema.sql"),
                new ClassPathResource("db/independent-answer-provenance-schema.sql")).execute(db);
        jdbc = new JdbcTemplate(db) {
            @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... arguments) {
                T result = super.query(sql, extractor, arguments);
                if (sql.contains("AS published_answer")) afterExportRead.get().run();
                return result;
            }
        };
        var tx = new DataSourceTransactionManager(db);
        AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) { return id == admin.id() ? admin : member; }
            public boolean workspaceEnabled(long id) { return id == 7; }
        };
        var configuration = new IntelligenceConfiguration();
        answers = configuration.chatAnswers(jdbc, Clock.systemUTC());
        feedback = configuration.feedback(jdbc, tx, Clock.systemUTC(), accounts, CourseKnowledgeAccess.unavailable(), answers);
    }

    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    @Test void resolvedPrivateAnswerCannotBypassPublicationPolicyThroughExport() {
        String messageId = answers.save(member.id(), member.workspaceId(), null,
                "Question containing a private note", "Private model response", true);
        FeedbackView reviewed = resolve(messageId, "Question containing a private note");
        legacyGlobalPublication(reviewed);

        assertTrue(feedback.exportReviewed(admin).isEmpty(),
                "A legacy GLOBAL publication must not turn private-origin feedback into shared training data");
    }

    @Test void missingPersistedSourceCannotBypassPublicationPolicyThroughExport() {
        FeedbackView reviewed = resolve("missing-source", "Question without a stored answer");
        legacyGlobalPublication(reviewed);

        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void currentPublicSourceExportsAlongsideExcludedPrivateAndMissingSources() {
        String publicId = answers.save(member.id(), member.workspaceId(), null,
                "Public product question", "Private model response", false);
        FeedbackView publishable = resolve(publicId, "Public product question");
        legacyGlobalPublication(publishable);
        String privateId = answers.save(member.id(), member.workspaceId(), null,
                "PRIVATE QUESTION", "Private model response", true);
        legacyGlobalPublication(resolve(privateId, "PRIVATE QUESTION"));
        legacyGlobalPublication(resolve("missing-private-source", "MISSING SOURCE QUESTION"));

        assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer(publishable.question(), publishable.correctedAnswer())),
                feedback.exportReviewed(admin));
    }

    @Test void sourceRemovalAfterPublicationImmediatelyStopsExport() {
        String messageId = answers.save(member.id(), member.workspaceId(), null,
                "Previously public product question", "Private model response", false);
        FeedbackView reviewed = resolve(messageId, "Previously public product question");
        legacyGlobalPublication(reviewed);
        assertEquals(1, feedback.exportReviewed(admin).size());

        jdbc.update("DELETE FROM sc_chat_answer WHERE message_id=?", messageId);
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void provenanceDatabaseFailureDoesNotReturnAPartialExport() {
        String messageId = answers.save(member.id(), member.workspaceId(), null,
                "Public question", "Private model response", false);
        legacyGlobalPublication(resolve(messageId, "Public question"));
        jdbc.execute("DROP TABLE sc_chat_answer");

        assertThrows(DataAccessException.class, () -> feedback.exportReviewed(admin));
    }

    @Test void sourceDeletedDuringFinalExportReadCannotReturnPreviouslyAllowedText() {
        String messageId = answers.save(member.id(), member.workspaceId(), null,
                "Public question removed during export", "Private model response", false);
        legacyGlobalPublication(resolve(messageId, "Public question removed during export"));
        afterExportRead.set(() -> jdbc.update("DELETE FROM sc_chat_answer WHERE message_id=?", messageId));

        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    private FeedbackView resolve(String messageId, String question) {
        var receipt = feedback.submit(member, question, "Private model response", messageId);
        return feedback.review(admin, receipt.id(), 1, "Human reviewed correction", FeedbackStatus.RESOLVED);
    }

    private void legacyGlobalPublication(FeedbackView source) {
        jdbc.update("INSERT INTO sc_reviewed_knowledge(feedback_id,title,answer,scope,version,source_feedback_version,created_at) VALUES(?,?,?,'GLOBAL',1,?,?)",
                source.id(), "Legacy publication", source.correctedAnswer(), source.version(), source.updatedAt());
    }
}

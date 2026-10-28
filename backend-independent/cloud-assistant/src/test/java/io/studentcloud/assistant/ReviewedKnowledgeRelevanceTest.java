package io.studentcloud.assistant;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedKnowledgeRelevanceTest {
    private final Actor admin = new Actor(1, "admin", "admin", null, Role.ADMIN, null);
    private final Actor member = new Actor(2, "member", "member", null, Role.USER, 7L);
    private final Actor other = new Actor(3, "other", "other", null, Role.USER, 8L);
    private AnswerFeedbackService feedback;
    private ReviewedKnowledgeService knowledge;

    @BeforeEach void setup() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:reviewed_relevance_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql")).execute(data);
        var jdbc = new JdbcTemplate(data);
        var tx = new DataSourceTransactionManager(data);
        AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) { return id == 1 ? admin : id == 2 ? member : other; }
            public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
        };
        CourseKnowledgeAccess courses = (actor, id) -> {
            if (actor.id() != member.id() || id != 12) throw new ApplicationFault("40300", 403, "课程不可访问");
        };
        feedback = new AnswerFeedbackService(jdbc, tx, Clock.systemUTC(), accounts, courses);
        knowledge = new ReviewedKnowledgeService(jdbc, tx, Clock.systemUTC(), accounts, courses);
    }

    @Test void chineseNaturalQuestionAndLongQuestionRetrieveReviewedHelp() {
        var published = publish(member, "上传文件失败的处理方法", "核对目标目录的上传权限和剩余容量。", KnowledgeScope.TENANT, null);
        assertEquals(published.id(), knowledge.search(member, "请问上传文件失败怎么办？", 5).getFirst().id());
        assertEquals(published.id(), knowledge.search(member, "如何".repeat(200) + "上传文件失败", 5).getFirst().id());
        assertEquals(400, assertThrows(ApplicationFault.class, () -> knowledge.search(member, "上".repeat(2001), 5)).httpStatus());
    }

    @Test void relevantOlderTitleOutranksRecentIncidentalBodyMention() {
        var focused = publish(member, "Upload files", "Check directory permission and quota.", KnowledgeScope.GLOBAL, null);
        publish(member, "General overview", "You can upload files after joining.", KnowledgeScope.GLOBAL, null);
        assertEquals(focused.id(), knowledge.search(member, "How do I upload files?", 1).getFirst().id());
    }

    @Test void scoreTiesHaveStableIdOrderRatherThanRecentInsertionOrder() {
        var first = publish(member, "Upload", "Check permissions.", KnowledgeScope.GLOBAL, null);
        var second = publish(member, "Upload", "Check permissions.", KnowledgeScope.GLOBAL, null);
        List<Long> expected = List.of(first.id(), second.id());
        assertEquals(expected, knowledge.search(member, "upload", 5).stream().map(KnowledgeHit::id).toList());
        assertEquals(expected, knowledge.search(member, "upload", 5).stream().map(KnowledgeHit::id).toList());
    }

    @Test void relevanceNeverIncludesRevokedOtherWorkspaceOrUnselectedCourseKnowledge() {
        var revoked = publish(member, "Upload files", "Revoked content", KnowledgeScope.GLOBAL, null);
        knowledge.revoke(admin, revoked.id(), revoked.version());
        var tenant = publish(member, "Upload files", "Workspace content", KnowledgeScope.TENANT, null);
        publish(other, "Upload files", "Other workspace content", KnowledgeScope.TENANT, null);
        var course = publish(member, "Upload files", "Course content", KnowledgeScope.COURSE, 12L);
        assertEquals(List.of(tenant.id()), knowledge.search(member, "upload files", 20).stream().map(KnowledgeHit::id).toList());
        assertTrue(knowledge.search(member, "upload files", 20, 12L).stream().anyMatch(hit -> hit.id() == course.id()));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> knowledge.search(other, "upload files", 20, 12L)).httpStatus());
    }

    @Test void supersededAnswerCannotBeRecalledEvenWhenItsOldTextWouldRankHigher() {
        var first = publish(member, "Database guide", "Legacy isolation instruction", KnowledgeScope.GLOBAL, null);
        var updated = feedback.review(admin, first.feedbackId(), 2, "Current transaction instruction", FeedbackStatus.RESOLVED);
        var replacement = knowledge.publish(admin, first.feedbackId(), updated.version(), "Database guide", KnowledgeScope.GLOBAL, null, true);
        assertTrue(knowledge.search(member, "legacy isolation", 5).isEmpty());
        assertEquals(replacement.id(), knowledge.search(member, "current transaction", 5).getFirst().id());
    }

    private KnowledgeView publish(Actor owner, String title, String answer, KnowledgeScope scope, Long courseId) {
        var receipt = feedback.submit(owner, title, "Unverified", UUID.randomUUID().toString(), courseId);
        var reviewed = feedback.review(admin, receipt.id(), 1, answer, FeedbackStatus.RESOLVED);
        return knowledge.publish(admin, receipt.id(), reviewed.version(), title, scope,
                scope == KnowledgeScope.TENANT ? owner.workspaceId() : null, scope == KnowledgeScope.GLOBAL);
    }
}

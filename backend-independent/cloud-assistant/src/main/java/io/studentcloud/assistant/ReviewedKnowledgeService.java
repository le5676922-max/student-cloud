package io.studentcloud.assistant;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Human-reviewed retrieval knowledge, never automatic publication or model-parameter training. */
public final class ReviewedKnowledgeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CourseKnowledgeAccess courses;
    private final AccountDirectory accounts;
    private final KnowledgeSourcePolicy sources;

    public ReviewedKnowledgeService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses) {
        this(jdbc,transactions,clock,accounts,courses,id->{});
    }
    public ReviewedKnowledgeService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses,KnowledgeSourcePolicy sources) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock);
        this.courses = Objects.requireNonNull(courses);
        this.accounts = Objects.requireNonNull(accounts);
        this.sources = Objects.requireNonNull(sources);
    }

    public KnowledgeView publish(Actor actor, long feedbackId, long expectedFeedbackVersion, String title,
                                  KnowledgeScope scope, Long workspaceId, boolean confirmGlobal) {
        return publish(actor, feedbackId, expectedFeedbackVersion, title, scope, workspaceId, confirmGlobal,
                null, null, null, false);
    }

    /** Browser review command compares both the reviewed answer and the current publication generation. */
    public KnowledgeView publishReviewed(Actor actor, long feedbackId, long expectedFeedbackVersion, long expectedKnowledgeVersion,
                                         long expectedUpdatedAt, String title, String reviewedAnswer, KnowledgeScope scope, boolean confirmGlobal) {
        if (expectedKnowledgeVersion < 0 || expectedUpdatedAt < 1)
            throw AssistantAccess.fault("41000", 400, "请先重新读取反馈与知识状态");
        return publish(actor, feedbackId, expectedFeedbackVersion, title, scope, null, confirmGlobal,
                expectedKnowledgeVersion, expectedUpdatedAt, AssistantAccess.text(reviewedAnswer, 12000, false), true);
    }

    private KnowledgeView publish(Actor actor, long feedbackId, long expectedFeedbackVersion, String title,
                                  KnowledgeScope scope, Long workspaceId, boolean confirmGlobal,
                                  Long expectedKnowledgeVersion, Long expectedUpdatedAt, String reviewedAnswer, boolean useSourceWorkspace) {
        String validTitle = AssistantAccess.text(title, 160, false);
        if (expectedFeedbackVersion < 1 || scope == null) throw AssistantAccess.fault("41000", 400, "发布版本或范围无效");
        if (scope == KnowledgeScope.GLOBAL && !confirmGlobal) throw AssistantAccess.fault("41000", 400, "发布全站知识需要明确确认");
        if (!useSourceWorkspace && scope == KnowledgeScope.TENANT && (workspaceId == null || workspaceId <= 0)) throw AssistantAccess.fault("41000", 400, "空间知识需要明确目标空间");
        return transactions.execute(status -> {
            AssistantAccess.administrator(accounts, actor);
            List<FeedbackView> feedback = jdbc.query("SELECT * FROM sc_answer_feedback WHERE id=? FOR UPDATE", AnswerFeedbackService::view, feedbackId);
            if (feedback.size() != 1) throw AssistantAccess.fault("40400", 404, "反馈不存在");
            AssistantAccess.administrator(accounts, actor);
            FeedbackView source = feedback.getFirst();
            sources.requirePublishable(feedbackId);
            if (source.courseId() != null && scope != KnowledgeScope.COURSE)
                throw AssistantAccess.fault("40300", 403, "课程反馈只能发布到原课程，不能转为空间或全站知识");
            if (source.courseId() == null && scope == KnowledgeScope.COURSE)
                throw AssistantAccess.fault("41000", 400, "此反馈没有课程来源，不能指定课程发布");
            if (source.version() != expectedFeedbackVersion) throw AssistantAccess.fault("41000", 409, "反馈已变化，请重新审核");
            if (expectedUpdatedAt != null && source.updatedAt() != expectedUpdatedAt
                    || reviewedAnswer != null && !source.correctedAnswer().equals(reviewedAnswer))
                throw AssistantAccess.fault("41000", 409, "反馈或修订答案已变化，请重新保存并审核");
            Long targetCourse = scope == KnowledgeScope.COURSE ? source.courseId() : null;
            Long targetWorkspace = scope != KnowledgeScope.TENANT ? null : useSourceWorkspace ? source.workspaceId() : workspaceId;
            if (source.status() != FeedbackStatus.RESOLVED || source.correctedAnswer().isBlank())
                throw AssistantAccess.fault("41000", 409, "请先核验正确答案并将反馈标记为已解决");
            if (scope == KnowledgeScope.TENANT) {
                if (targetWorkspace == null) throw AssistantAccess.fault("41000", 400, "此反馈没有所属空间，请核对是否可作为全站通用知识");
                if (!Objects.equals(source.workspaceId(), targetWorkspace)) throw AssistantAccess.fault("40300", 403, "不能将此反馈发布到其他空间");
                if (!accounts.workspaceEnabled(targetWorkspace)) throw AssistantAccess.fault("41000", 409, "目标空间已停用或不存在");
            }
            List<PublishedRow> active = jdbc.query("SELECT * FROM sc_reviewed_knowledge WHERE feedback_id=? AND revoked_at IS NULL AND superseded_at IS NULL ORDER BY version DESC",
                    ReviewedKnowledgeService::published, feedbackId);
            long generation = Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM sc_reviewed_knowledge WHERE feedback_id=?", Long.class, feedbackId));
            boolean duplicate = false;
            if (active.size() == 1) {
                PublishedRow existing = active.getFirst();
                KnowledgeView view = existing.view();
                if (existing.sourceVersion() == source.version() && view.title().equals(validTitle) && view.answer().equals(source.correctedAnswer())
                        && view.scope() == scope && Objects.equals(view.workspaceId(), targetWorkspace)
                        && Objects.equals(view.courseId(), targetCourse)) duplicate = true;
            }
            // A retry of exactly the command that produced the current version is safe; stale distinct edits are not.
            if (expectedKnowledgeVersion != null && expectedKnowledgeVersion != generation
                    && !(duplicate && expectedKnowledgeVersion == generation - 1)) throw changed();
            if (duplicate) return active.getFirst().view();
            long nextVersion = generation + 1;
            if (nextVersion < 1) throw AssistantAccess.fault("41000", 409, "知识版本需要维护");
            long now = clock.millis();
            jdbc.update("UPDATE sc_reviewed_knowledge SET superseded_at=? WHERE feedback_id=? AND revoked_at IS NULL AND superseded_at IS NULL", now, feedbackId);
            long id = AssistantAccess.insertId(jdbc, "INSERT INTO sc_reviewed_knowledge(feedback_id,title,answer,scope,workspace_id,course_id,version,source_feedback_version,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    feedbackId, validTitle, source.correctedAnswer(), scope.name(), targetWorkspace, targetCourse, nextVersion, source.version(), now);
            return new KnowledgeView(id, feedbackId, validTitle, source.correctedAnswer(), scope, targetWorkspace, targetCourse, nextVersion, null, null, now);
        });
    }

    public List<KnowledgeView> list(Actor actor, int offset, int limit) {
        AssistantAccess.administrator(accounts, actor);
        AssistantAccess.page(offset, limit);
        return jdbc.query("SELECT * FROM sc_reviewed_knowledge ORDER BY id DESC LIMIT ? OFFSET ?", (r, index) -> published(r, index).view(), limit, offset);
    }

    public List<KnowledgeView> history(Actor actor, long feedbackId) {
        AssistantAccess.administrator(accounts, actor);
        requireFeedback(feedbackId, false);
        return jdbc.query("SELECT * FROM sc_reviewed_knowledge WHERE feedback_id=? ORDER BY version DESC LIMIT 50",
                (r, index) -> published(r, index).view(), feedbackId);
    }

    public PublicationState state(Actor actor, long feedbackId) {
        AssistantAccess.administrator(accounts, actor);
        requireFeedback(feedbackId, false);
        return state(feedbackId);
    }

    public PublicationState withdraw(Actor actor, long feedbackId, long expectedVersion) {
        if (expectedVersion < 1) throw AssistantAccess.fault("41000", 400, "知识版本无效");
        return transactions.execute(transaction -> {
            AssistantAccess.administrator(accounts, actor);
            requireFeedback(feedbackId, true);
            AssistantAccess.administrator(accounts, actor);
            PublicationState current = state(feedbackId);
            if (current.version() != expectedVersion) throw changed();
            jdbc.update("UPDATE sc_reviewed_knowledge SET revoked_at=? WHERE feedback_id=? AND version=? AND revoked_at IS NULL AND superseded_at IS NULL",
                    clock.millis(), feedbackId, expectedVersion);
            return state(feedbackId);
        });
    }

    private PublicationState state(long feedbackId) {
        List<KnowledgeView> latest = jdbc.query("SELECT * FROM sc_reviewed_knowledge WHERE feedback_id=? ORDER BY version DESC LIMIT 1",
                (r, index) -> published(r, index).view(), feedbackId);
        if (latest.isEmpty()) return new PublicationState(feedbackId, "UNPUBLISHED", 0, null);
        KnowledgeView view = latest.getFirst();
        return new PublicationState(feedbackId, view.revokedAt() == null && view.supersededAt() == null ? "PUBLISHED" : "WITHDRAWN", view.version(), view.scope());
    }

    private void requireFeedback(long feedbackId, boolean lock) {
        List<Long> rows = jdbc.queryForList("SELECT id FROM sc_answer_feedback WHERE id=?" + (lock ? " FOR UPDATE" : ""), Long.class, feedbackId);
        if (rows.size() != 1) throw AssistantAccess.fault("40400", 404, "反馈不存在");
    }

    public record PublicationState(long feedbackId, String state, long version, KnowledgeScope scope) { }

    private static io.studentcloud.common.ApplicationFault changed() {
        return AssistantAccess.fault("41000", 409, "知识版本已变化，请重新读取后操作");
    }

    public void revoke(Actor actor, long knowledgeId, long expectedVersion) {
        if (expectedVersion < 1) throw AssistantAccess.fault("41000", 400, "知识版本无效");
        transactions.executeWithoutResult(status -> {
            AssistantAccess.administrator(accounts, actor);
            List<Long> feedbackIds = jdbc.queryForList("SELECT feedback_id FROM sc_reviewed_knowledge WHERE id=?", Long.class, knowledgeId);
            if (feedbackIds.size() != 1) throw AssistantAccess.fault("40400", 404, "知识版本不存在");
            requireFeedback(feedbackIds.getFirst(), true);
            List<PublishedRow> rows = jdbc.query("SELECT * FROM sc_reviewed_knowledge WHERE id=? FOR UPDATE", ReviewedKnowledgeService::published, knowledgeId);
            if (rows.size() != 1) throw AssistantAccess.fault("40400", 404, "知识版本不存在");
            AssistantAccess.administrator(accounts, actor);
            KnowledgeView existing = rows.getFirst().view();
            if (existing.version() != expectedVersion) throw AssistantAccess.fault("41000", 409, "知识版本不匹配，请刷新");
            jdbc.update("UPDATE sc_reviewed_knowledge SET revoked_at=? WHERE id=? AND revoked_at IS NULL", clock.millis(), knowledgeId);
        });
    }

    /** Reads at most 500 recent, scoped active candidates; it never searches private original feedback. */
    public List<KnowledgeHit> search(Actor actor, String query, int limit) {
        return search(actor, query, limit, null);
    }

    /** A course context is explicit; without it course knowledge is never queried. */
    public List<KnowledgeHit> search(Actor actor, String query, int limit, Long courseId) {
        Actor current = AssistantAccess.current(accounts, actor);
        String value = AssistantAccess.text(query, KnowledgeTextMatcher.MAX_QUERY_CHARS, false);
        if (limit < 1 || limit > 20) throw AssistantAccess.fault("41000", 400, "知识检索数量须在 1 到 20 之间");
        if (courseId != null) {
            if (courseId < 1) throw AssistantAccess.fault("41000", 400, "课程标识无效");
            courses.requireRead(current, courseId);
        }
        var terms = KnowledgeTextMatcher.query(value);
        Long selectedWorkspace = current.workspaceId();
        String publicScopes = "(scope='GLOBAL' AND course_id IS NULL AND workspace_id IS NULL) OR (scope='TENANT' AND course_id IS NULL AND workspace_id=?)";
        List<KnowledgeView> candidates = courseId == null
                ? jdbc.query("SELECT * FROM sc_reviewed_knowledge WHERE revoked_at IS NULL AND superseded_at IS NULL AND (" + publicScopes + ") ORDER BY id DESC LIMIT 500",
                        (r, index) -> published(r, index).view(), current.workspaceId())
                : jdbc.query("SELECT * FROM sc_reviewed_knowledge WHERE revoked_at IS NULL AND superseded_at IS NULL AND (" + publicScopes + " OR (scope='COURSE' AND workspace_id IS NULL AND course_id=?)) ORDER BY id DESC LIMIT 500",
                        (r, index) -> published(r, index).view(), current.workspaceId(), courseId);
        current = AssistantAccess.current(accounts, current);
        if (!Objects.equals(selectedWorkspace, current.workspaceId()))
            throw AssistantAccess.fault("41000", 409, "所属空间已变化，请重新检索");
        if (courseId != null) courses.requireRead(current, courseId);
        return candidates.stream().map(entry -> new ScoredKnowledge(entry, KnowledgeTextMatcher.score(terms, entry.title(), entry.answer())))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredKnowledge::score).reversed().thenComparingLong(hit -> hit.entry().id()))
                .limit(limit).map(hit -> new KnowledgeHit(hit.entry().id(), hit.entry().title(), hit.entry().answer(), hit.entry().version(), hit.entry().scope())).toList();
    }

    private record ScoredKnowledge(KnowledgeView entry, double score) { }

    private static PublishedRow published(ResultSet r, int index) throws SQLException {
        KnowledgeView view = new KnowledgeView(r.getLong("id"), r.getLong("feedback_id"), r.getString("title"), r.getString("answer"), KnowledgeScope.valueOf(r.getString("scope")),
                r.getObject("workspace_id", Long.class), r.getObject("course_id", Long.class), r.getLong("version"), r.getObject("revoked_at", Long.class), r.getObject("superseded_at", Long.class), r.getLong("created_at"));
        return new PublishedRow(view, r.getLong("source_feedback_version"));
    }
    private record PublishedRow(KnowledgeView view, long sourceVersion) { }
}

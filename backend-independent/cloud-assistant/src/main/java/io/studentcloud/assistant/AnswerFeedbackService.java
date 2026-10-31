package io.studentcloud.assistant;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.common.ApplicationFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 记录未解决回答、管理人工修订，并按来源与发布范围限制知识导出。
 *
 * User-selected unresolved turns, without automatic transfer, model training or history ingestion.
 */
public final class AnswerFeedbackService {
    private static final String EXPORTABLE_KNOWLEDGE = """
            FROM sc_reviewed_knowledge k JOIN sc_answer_feedback f ON f.id=k.feedback_id
            WHERE k.scope='GLOBAL' AND k.workspace_id IS NULL AND k.course_id IS NULL
              AND k.revoked_at IS NULL AND k.superseded_at IS NULL
              AND f.status='RESOLVED' AND f.course_id IS NULL AND f.corrected_answer<>''
              AND k.source_feedback_version=f.version AND k.answer=f.corrected_answer
              AND NOT EXISTS (SELECT 1 FROM sc_reviewed_knowledge newer
                              WHERE newer.feedback_id=k.feedback_id AND newer.version>k.version)
            """;
    private static final String EXPORT_ORDER = " ORDER BY f.updated_at DESC,f.id DESC LIMIT 500";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CourseKnowledgeAccess courses;
    private final AccountDirectory accounts;
    private final KnowledgeSourcePolicy sources;

    public AnswerFeedbackService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses) {
        this(jdbc, transactions, clock, accounts, courses, id -> {
            throw AssistantAccess.fault("KNOWLEDGE_SOURCE_POLICY_UNAVAILABLE", 503, "知识来源策略不可用，无法导出");
        });
    }

    public AnswerFeedbackService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses, KnowledgeSourcePolicy sources) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock);
        this.courses = Objects.requireNonNull(courses);
        this.accounts = Objects.requireNonNull(accounts);
        this.sources = Objects.requireNonNull(sources);
    }

    public FeedbackReceipt submit(Actor actor, String question, String answer, String messageId) {
        return submit(actor, question, answer, messageId, null);
    }

    public FeedbackReceipt submit(Actor actor, String question, String answer, String messageId, Long courseId) {
        String q = AssistantAccess.text(question, 2000, false);
        String a = AssistantAccess.text(answer, 12000, false);
        if (messageId == null || !messageId.matches("[A-Za-z0-9_-]{1,100}")) throw AssistantAccess.fault("41000", 400, "消息标识无效");
        if (courseId != null && courseId < 1) throw AssistantAccess.fault("41000", 400, "课程标识无效");
        return transactions.execute(status -> {
            Actor current = AssistantAccess.current(accounts, actor);
            AssistantAccess.ownerLock(jdbc, current.id());
            current = AssistantAccess.current(accounts, current);
            if (courseId != null) courses.requireRead(current, courseId);
            List<FeedbackView> previous = jdbc.query("SELECT * FROM sc_answer_feedback WHERE account_id=? AND message_id=?", AnswerFeedbackService::view, current.id(), messageId);
            if (!previous.isEmpty()) {
                FeedbackView existing = previous.getFirst();
                if (!existing.question().equals(q) || !existing.answer().equals(a) || !Objects.equals(existing.courseId(), courseId))
                    throw AssistantAccess.fault("41000", 409, "这条消息已提交其他内容，请核对后重试");
                return new FeedbackReceipt(existing.id(), existing.status(), existing.createdAt(), true);
            }
            long now = clock.millis();
            long id = AssistantAccess.insertId(jdbc, "INSERT INTO sc_answer_feedback(account_id,workspace_id,course_id,message_id,question,answer,corrected_answer,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'','OPEN',1,?,?)",
                    current.id(), current.workspaceId(), courseId, messageId, q, a, now, now);
            return new FeedbackReceipt(id, FeedbackStatus.OPEN, now, false);
        });
    }

    public List<FeedbackView> own(Actor actor, int offset, int limit) {
        Actor current = AssistantAccess.current(accounts, actor);
        AssistantAccess.page(offset, limit);
        return jdbc.query("SELECT * FROM sc_answer_feedback WHERE account_id=? ORDER BY id DESC LIMIT ? OFFSET ?", AnswerFeedbackService::view, current.id(), limit, offset);
    }

    public List<FeedbackView> list(Actor actor, FeedbackStatus status, int offset, int limit) {
        AssistantAccess.administrator(accounts, actor);
        AssistantAccess.page(offset, limit);
        return status == null
                ? jdbc.query("SELECT * FROM sc_answer_feedback ORDER BY id DESC LIMIT ? OFFSET ?", AnswerFeedbackService::view, limit, offset)
                : jdbc.query("SELECT * FROM sc_answer_feedback WHERE status=? ORDER BY id DESC LIMIT ? OFFSET ?", AnswerFeedbackService::view, status.name(), limit, offset);
    }

    public FeedbackView detail(Actor actor, long id) {
        AssistantAccess.administrator(accounts, actor);
        return feedback(id, false);
    }

    public long count(Actor actor, FeedbackStatus status) {
        AssistantAccess.administrator(accounts, actor);
        return Objects.requireNonNull(status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM sc_answer_feedback", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM sc_answer_feedback WHERE status=?", Long.class, status.name()));
    }

    /** Only current GLOBAL publications with permitted provenance enter this bounded manual export. */
    public List<ReviewedAnswer> exportReviewed(Actor actor) {
        exportAdministrator(actor);
        // Bound provenance work before checking sources; denied rows never trigger an unbounded refill.
        List<ExportCandidate> candidates = jdbc.query("SELECT k.id,k.feedback_id " + EXPORTABLE_KNOWLEDGE + EXPORT_ORDER,
                (r, index) -> new ExportCandidate(r.getLong("id"), r.getLong("feedback_id")));
        List<Long> allowed = new ArrayList<>();
        for (ExportCandidate candidate : candidates) {
            if (exportableSource(candidate.feedbackId())) allowed.add(candidate.knowledgeId());
        }
        // Re-read lifecycle and review state after provenance checks, then check the current administrator.
        // Only server-owned knowledge IDs enter this query; denied candidate text is never read for export.
        List<ExportRow> current = allowed.isEmpty() ? List.of() : jdbc.query(
                "SELECT f.id AS feedback_id,f.question,f.corrected_answer,k.answer AS published_answer " + EXPORTABLE_KNOWLEDGE
                        + " AND k.id IN (" + String.join(",", Collections.nCopies(allowed.size(), "?")) + ")" + EXPORT_ORDER,
                r -> {
                    List<ExportRow> rows = new ArrayList<>();
                    while (r.next()) {
                        String correction = r.getString("corrected_answer");
                        // MySQL TEXT equality may ignore case or accents; review matching must be exact.
                        if (correction.equals(r.getString("published_answer")))
                            rows.add(new ExportRow(r.getLong("feedback_id"), new ReviewedAnswer(r.getString("question"), correction)));
                    }
                    return rows;
                }, allowed.toArray());
        List<ReviewedAnswer> result = new ArrayList<>();
        // Recheck immutable provenance/existence after reading text. This is a boundary recheck,
        // not an atomic snapshot of remote identity or later source deletions (at most 1000 checks).
        for (ExportRow row : current) if (exportableSource(row.feedbackId())) result.add(row.answer());
        exportAdministrator(actor);
        return List.copyOf(result);
    }

    private void exportAdministrator(Actor actor) {
        Actor current = AssistantAccess.administrator(accounts, actor);
        if (current.id() != actor.id() || !Objects.equals(current.accountId(), actor.accountId()))
            throw AssistantAccess.fault("42000", 401, "账号已变化，请重新登录");
    }

    private boolean exportableSource(long feedbackId) {
        try {
            sources.requirePublishable(feedbackId);
            return true;
        } catch (ApplicationFault fault) {
            // Provenance denial excludes one row; unavailable policy/storage must fail the export.
            if (fault.httpStatus() == 403) return false;
            throw fault;
        }
    }

    private record ExportCandidate(long knowledgeId, long feedbackId) { }
    private record ExportRow(long feedbackId, ReviewedAnswer answer) { }

    public record ReviewedAnswer(String question, String answer) { }

    public FeedbackView review(Actor actor, long id, long expectedVersion, String correctedAnswer, FeedbackStatus status) {
        return review(actor, id, expectedVersion, correctedAnswer, null, status);
    }

    public FeedbackView review(Actor actor, long id, long expectedVersion, String correctedAnswer, String note, FeedbackStatus status) {
        String answer = AssistantAccess.text(correctedAnswer, 12000, true);
        String validatedNote = note == null ? null : AssistantAccess.text(note, 2000, true);
        if (expectedVersion < 1 || expectedVersion == Long.MAX_VALUE || status == null) throw AssistantAccess.fault("41000", 400, "反馈状态或版本无效");
        if (status == FeedbackStatus.RESOLVED && answer.isBlank()) throw AssistantAccess.fault("41000", 400, "标记已解决前请填写正确答案");
        return transactions.execute(transaction -> {
            AssistantAccess.administrator(accounts, actor);
            FeedbackView existing = feedback(id, true);
            AssistantAccess.administrator(accounts, actor);
            if (existing.version() != expectedVersion) throw stale();
            long updatedAt = Math.max(clock.millis(), Math.addExact(existing.updatedAt(), 1));
            if (jdbc.update("UPDATE sc_answer_feedback SET corrected_answer=?,note=?,status=?,version=version+1,updated_at=? WHERE id=? AND version=?", answer,
                    validatedNote == null ? existing.note() : validatedNote, status.name(), updatedAt, id, expectedVersion) != 1) throw stale();
            return feedback(id, false);
        });
    }

    private FeedbackView feedback(long id, boolean lock) {
        List<FeedbackView> rows = jdbc.query("SELECT * FROM sc_answer_feedback WHERE id=?" + (lock ? " FOR UPDATE" : ""), AnswerFeedbackService::view, id);
        if (rows.size() != 1) throw AssistantAccess.fault("40400", 404, "反馈不存在");
        return rows.getFirst();
    }

    static FeedbackView view(ResultSet r, int index) throws SQLException {
        return new FeedbackView(r.getLong("id"), r.getString("question"), r.getString("answer"), r.getString("corrected_answer"), r.getString("note"), FeedbackStatus.valueOf(r.getString("status")),
                r.getObject("workspace_id", Long.class), r.getObject("course_id", Long.class), r.getLong("version"), r.getLong("created_at"), r.getLong("updated_at"));
    }

    private static io.studentcloud.common.ApplicationFault stale() { return AssistantAccess.fault("41000", 409, "反馈已变化，请刷新后重新整理"); }
}

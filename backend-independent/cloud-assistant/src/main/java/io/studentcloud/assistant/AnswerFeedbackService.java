package io.studentcloud.assistant;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** User-selected unresolved turns, without automatic transfer, model training or history ingestion. */
public final class AnswerFeedbackService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CourseKnowledgeAccess courses;
    private final AccountDirectory accounts;

    public AnswerFeedbackService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock);
        this.courses = Objects.requireNonNull(courses);
        this.accounts = Objects.requireNonNull(accounts);
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

    /** Bounded manual export; contains no identities, administrative notes or original model answers. */
    public List<ReviewedAnswer> exportReviewed(Actor actor) {
        AssistantAccess.administrator(accounts, actor);
        return jdbc.query("SELECT question,corrected_answer FROM sc_answer_feedback WHERE status='RESOLVED' AND course_id IS NULL AND corrected_answer<>'' ORDER BY updated_at DESC,id DESC LIMIT 500",
                (r, index) -> new ReviewedAnswer(r.getString("question"), r.getString("corrected_answer")));
    }

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

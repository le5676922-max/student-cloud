package im.tlgxy.cloud.module.ai.feedback;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeDocument;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.course.SharedCourseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reviewed Q&A publishing. Original model answers are never used as knowledge automatically. */
@Service
public class AiKnowledgePublicationService {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final AiFeedbackService feedback;
    private final AiKnowledgeRefiner refiner;
    private final TransactionTemplate transactions;
    private SharedCourseService courses;

    @Autowired(required=false)
    public void setCourses(SharedCourseService courses) { this.courses = courses; }

    public AiKnowledgePublicationService(JdbcTemplate jdbc, UserService users, AiFeedbackService feedback,
                                         AiKnowledgeRefiner refiner, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.users = users; this.feedback = feedback; this.refiner = refiner;
        transactions = new TransactionTemplate(manager);
    }

    public record State(String feedbackId, int version, String state, long updatedAt) { }
    public record Revision(String feedbackId, int version, String scope, Integer tenantId, String title,
                           String question, String answer, String tags, long sourceUpdatedAt,
                           Integer publishedBy, long publishedAt, String courseId) { }
    public record RefineRequest(String feedbackId, long expectedUpdatedAt, String administratorAnswer) { }
    public record Preview(String answer, boolean requiresReview, long sourceUpdatedAt) { }
    public record PublishEntry(String feedbackId, long expectedUpdatedAt, int expectedVersion,
                               String title, String answer, String tags, String scope, boolean confirmGlobal) { }
    public record PublishRequest(List<PublishEntry> entries) { }
    public record Withdrawal(String feedbackId, int expectedVersion) { }

    public State state(int actorId, String id) {
        admin(actorId); feedback.get(actorId, id);
        return current(id);
    }

    public List<Revision> history(int actorId, String id) {
        admin(actorId); feedback.get(actorId, id);
        return jdbc.query("SELECT * FROM ai_curated_knowledge_revision WHERE feedback_id=? ORDER BY version DESC LIMIT 50",
                (row, index) -> new Revision(row.getString("feedback_id"), row.getInt("version"), row.getString("scope"),
                        (Integer) row.getObject("tenant_id"), row.getString("title"), row.getString("question"),
                        row.getString("answer"), row.getString("tags"), row.getLong("source_updated_at"),
                        (Integer) row.getObject("published_by"), row.getLong("published_at"), row.getString("course_id")), id);
    }

    /** This one explicitly invoked request is the only external AI boundary. */
    public Preview refine(int actorId, RefineRequest request) {
        admin(actorId);
        if (request == null) throw new BizException("请填写需要整理的回答");
        var source = feedback.get(actorId, request.feedbackId());
        if (source.updatedAt() != request.expectedUpdatedAt()) stale();
        String answer = text(request.administratorAnswer(), 12000, "管理员核对后的答案");
        String refined = refiner.refine(sanitize(source.question()), sanitize(answer));
        // Re-check identity and source after the remote request, before returning any private text.
        admin(actorId);
        if (feedback.get(actorId, request.feedbackId()).updatedAt() != source.updatedAt()) stale();
        return new Preview(sanitize(text(refined, 12000, "AI 整理结果")), true, source.updatedAt());
    }

    /** All entries commit together; stale source/version makes the entire batch fail. */
    public List<State> publish(int actorId, PublishRequest request) {
        admin(actorId);
        if (request == null || request.entries() == null || request.entries().isEmpty() || request.entries().size() > 50)
            throw new BizException("每批请选择 1 至 50 条已核对的问答");
        var identifiers = new HashSet<String>();
        for (var entry : request.entries()) {
            if (entry == null) throw new BizException("发布条目不正确");
            validateId(entry.feedbackId());
            if (!identifiers.add(entry.feedbackId()) || entry.expectedVersion() < 0)
                throw new BizException("发布条目重复或版本不正确");
        }
        // Stable lock order prevents two overlapping administrator batches from deadlocking.
        List<PublishEntry> entries = request.entries().stream().sorted(java.util.Comparator.comparing(PublishEntry::feedbackId)).toList();
        try {
            return transactions.execute(ignored -> {
                admin(actorId);
                List<State> published = new ArrayList<>();
                for (var entry : entries) published.add(publishOne(actorId, entry));
                return List.copyOf(published);
            });
        } catch (DataIntegrityViolationException concurrent) {
            throw new BizException("知识版本已变化，请重新读取后确认发布");
        }
    }

    private State publishOne(int actorId, PublishEntry entry) {
        // Locks the source row on MySQL and the writer transaction on SQLite. Never alter the correction here.
        jdbc.update("UPDATE ai_answer_feedback SET reviewed_by=reviewed_by WHERE id=? AND updated_at=?",
                entry.feedbackId(), entry.expectedUpdatedAt());
        var source = feedback.get(actorId, entry.feedbackId());
        if (source.updatedAt() != entry.expectedUpdatedAt()) stale();
        if (!"RESOLVED".equals(source.status()) || source.correctedAnswer() == null || source.correctedAnswer().isBlank())
            throw new BizException("请先人工核对并保存为已整理，再发布到知识库");
        String answer = text(entry.answer(), 12000, "待发布回答");
        if (!answer.equals(source.correctedAnswer().strip()))
            throw new BizException("待发布答案与已保存的人工修订不同，请先保存整理结果");
        String scope = entry.scope() == null || entry.scope().isBlank()
                ? source.courseId() == null ? "TENANT" : "COURSE" : entry.scope();
        if (!List.of("TENANT", "GLOBAL", "COURSE").contains(scope)) throw new BizException("知识发布范围不正确");
        if (source.courseId() != null && !"COURSE".equals(scope))
            throw new BizException("课程反馈只能发布回原课程，不能扩散到整个空间或全站");
        if ("COURSE".equals(scope) && (source.courseId() == null || courses == null || !courses.isActive(source.courseId())))
            throw new BizException("原反馈课程不可用，不能发布课程知识");
        if ("GLOBAL".equals(scope) && !entry.confirmGlobal())
            throw new BizException("请确认内容为不含私人资料的全站通用知识");
        if ("TENANT".equals(scope) && (source.tenantId() == null || !enabledTenant(source.tenantId())))
            throw new BizException("原反馈空间不可用，不能发布空间知识");
        State current = current(entry.feedbackId());
        if (current.version() != entry.expectedVersion()) stale();
        int version = Math.addExact(current.version(), 1);
        long now = System.currentTimeMillis();
        if (current.version() == 0) {
            jdbc.update("INSERT INTO ai_curated_knowledge(feedback_id,version,state,updated_at) VALUES (?,?,'PUBLISHED',?)",
                    entry.feedbackId(), version, now);
        } else if (jdbc.update("UPDATE ai_curated_knowledge SET version=?,state='PUBLISHED',updated_at=? WHERE feedback_id=? AND version=?",
                version, now, entry.feedbackId(), current.version()) != 1) stale();
        String title = entry.title() == null || entry.title().isBlank() ? source.question().substring(0, Math.min(160, source.question().length()))
                : text(entry.title(), 160, "知识标题");
        String tags = normalizeTags(entry.tags());
        jdbc.update("INSERT INTO ai_curated_knowledge_revision(feedback_id,version,scope,tenant_id,title,question,answer,tags,source_updated_at,published_by,published_at,course_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                entry.feedbackId(), version, scope, "TENANT".equals(scope) ? source.tenantId() : null,
                sanitize(title), sanitize(source.question()), sanitize(answer), tags, source.updatedAt(), actorId, now,
                "COURSE".equals(scope) ? source.courseId() : null);
        return current(entry.feedbackId());
    }

    public State withdraw(int actorId, Withdrawal request) {
        admin(actorId);
        if (request == null || request.expectedVersion() < 1) throw new BizException("知识版本不正确");
        validateId(request.feedbackId());
        return transactions.execute(ignored -> {
            admin(actorId); feedback.get(actorId, request.feedbackId());
            int changed = jdbc.update("UPDATE ai_curated_knowledge SET state='WITHDRAWN',updated_at=? WHERE feedback_id=? AND version=? AND state='PUBLISHED'",
                    System.currentTimeMillis(), request.feedbackId(), request.expectedVersion());
            State state = current(request.feedbackId());
            if (changed == 0 && (state.version() != request.expectedVersion() || !"WITHDRAWN".equals(state.state()))) stale();
            return state;
        });
    }

    /** Fresh visibility on every search: no stale shared result cache, no identity metadata in returned documents. */
    public List<KnowledgeDocument> visibleDocuments(KnowledgeAudience audience) {
        return visibleDocuments(TlgxyCloudAuthUtil.getCurrentUserId(), audience);
    }

    public List<KnowledgeDocument> visibleDocuments(int actorId, KnowledgeAudience audience) {
        User user = users.requireLoginUser(actorId);
        if (user == null || !Objects.equals(user.getId(), actorId) || !Boolean.TRUE.equals(user.getEnable()))
            throw new BizException("当前账号不可用");
        KnowledgeAudience actual = user.getRole() == UserRoleEnum.ADMIN ? KnowledgeAudience.ADMIN : KnowledgeAudience.USER;
        if (audience != actual) throw new BizException("不能访问其他账号类型的知识库");
        Integer tenantId = user.getRole() == UserRoleEnum.ADMIN ? null : user.getTenantId();
        if (user.getRole() != UserRoleEnum.ADMIN && (tenantId == null || !enabledTenant(tenantId)))
            throw new BizException("当前工作空间不可用");
        String courseVisibility = courses == null || user.getRole() == UserRoleEnum.ADMIN ? "" :
                " OR (r.scope='COURSE' AND r.course_id=f.course_id AND EXISTS (SELECT 1 FROM shared_course c "
                        + "WHERE c.id=r.course_id AND c.enabled=1 AND (c.owner_user_id=? OR EXISTS "
                        + "(SELECT 1 FROM shared_course_member m WHERE m.course_id=c.id AND m.user_id=? AND m.status='JOINED'))))";
        List<Object> params = new ArrayList<>(); params.add(tenantId);
        if (!courseVisibility.isEmpty()) { params.add(actorId); params.add(actorId); }
        var courseDecisions = new java.util.HashMap<String, Boolean>();
        record VisibleRow(String courseId, KnowledgeDocument document) { }
        List<VisibleRow> rows = jdbc.query("SELECT r.feedback_id,r.version,r.title,r.question,r.answer,r.tags,r.course_id FROM ai_curated_knowledge k "
                        + "JOIN ai_curated_knowledge_revision r ON r.feedback_id=k.feedback_id AND r.version=k.version "
                        + "JOIN ai_answer_feedback f ON f.id=k.feedback_id AND f.status='RESOLVED' AND f.updated_at=r.source_updated_at "
                        + "WHERE k.state='PUBLISHED' AND (r.scope='GLOBAL' OR (r.scope='TENANT' AND r.tenant_id=?)" + courseVisibility + ") "
                        + "ORDER BY k.updated_at DESC,k.feedback_id LIMIT 1000", (row, index) -> {
            String courseId = row.getString("course_id");
            KnowledgeDocument document = new KnowledgeDocument();
            document.setId("curated-" + row.getString("feedback_id") + "-v" + row.getInt("version"));
            document.setTitle(row.getString("title"));
            document.setQuestion(row.getString("question"));
            document.setContent("问题：" + row.getString("question") + "\n回答：" + row.getString("answer"));
            document.setTags(Arrays.asList(row.getString("tags").split(",")));
            document.setSource("经管理员核对的知识条目，版本 " + row.getInt("version"));
            document.setAudience(audience.getKey());
            return new VisibleRow(courseId, document);
        }, params.toArray());
        // Release the result-set connection before checking course authorization. A JDBC
        // call from inside a row mapper deadlocks a pool of size one under real MySQL.
        return rows.stream().filter(row -> row.courseId() == null || courses != null
                && courseDecisions.computeIfAbsent(row.courseId(), key -> courses.canRead(actorId, key)))
                .map(VisibleRow::document).toList();
    }

    private State current(String id) {
        List<State> rows = jdbc.query("SELECT feedback_id,version,state,updated_at FROM ai_curated_knowledge WHERE feedback_id=?",
                (row, index) -> new State(row.getString("feedback_id"), row.getInt("version"), row.getString("state"), row.getLong("updated_at")), id);
        return rows.isEmpty() ? new State(id, 0, "UNPUBLISHED", 0) : rows.getFirst();
    }
    private boolean enabledTenant(Integer tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE id=? AND enable=1", Integer.class, tenantId);
        return count != null && count == 1;
    }
    private void admin(int id) {
        User user = users.requireLoginUser(id);
        if (user == null || !Objects.equals(user.getId(), id) || !Boolean.TRUE.equals(user.getEnable()) || user.getRole() != UserRoleEnum.ADMIN)
            throw new BizException("仅全局管理员可以整理和发布知识");
    }
    private static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new BizException("反馈标识不正确");
    }
    private static void stale() { throw new BizException("反馈或知识版本已变化，请重新读取后操作"); }
    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new BizException(label + "不能为空且长度不能超过 " + max);
        return value.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").strip();
    }
    private static String normalizeTags(String value) {
        if (value == null || value.isBlank()) return "人工整理,学生云盘";
        if (value.length() > 300) throw new BizException("标签过长");
        List<String> tags = Arrays.stream(value.split("[,，]" )).map(String::strip).filter(tag -> !tag.isBlank())
                .map(AiKnowledgePublicationService::sanitize).distinct().toList();
        if (tags.isEmpty() || tags.size() > 10 || tags.stream().anyMatch(tag -> tag.length() > 30))
            throw new BizException("请填写不超过 10 个标签，每个不超过 30 字");
        return String.join(",", tags);
    }
    static String sanitize(String text) {
        return AiFeedbackService.redact(text).replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[邮箱已隐藏]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号已隐藏]")
                .replaceAll("(?i)\\bSC(?:[A-F0-9]{16}|\\d{6,})\\b", "[账号已隐藏]");
    }
}

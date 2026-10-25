package im.tlgxy.cloud.module.ai.feedback;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.course.SharedCourseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Explicitly user-submitted answer feedback, never a verified server transcript or model training job. */
@Service
public class AiFeedbackService {
    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "DISMISSED");
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{12,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[a-z0-9._~+/-]+=*");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?im)((?:\\b(?:password|passwd|pwd|api[_-]?key|access[_-]?token|authorization)\\b|密码|授权码|验证码)\\s*[:=：]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;，；]+)");
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final TransactionTemplate transactions;
    private final Object[] stripes = new Object[64];
    private SharedCourseService courses;

    @Autowired(required=false)
    public void setCourses(SharedCourseService courses) { this.courses = courses; }

    public AiFeedbackService(JdbcTemplate jdbc, UserService users, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.transactions = new TransactionTemplate(transactionManager);
        for (int index = 0; index < stripes.length; index++) stripes[index] = new Object();
    }

    public record Submission(String id, String status, long createdAt) { }
    public record Summary(String id, int userId, String accountId, String nickname, Integer tenantId,
                          String tenantName, String questionPreview, String status, String source,
                          long createdAt, long updatedAt) { }
    public record Detail(String id, int userId, String accountId, String nickname, Integer tenantId,
                         String tenantName, String question, String answer, String pagePath, String status,
                         String correctedAnswer, String note, String source, Integer reviewedBy,
                         long createdAt, long updatedAt, String courseId) { }
    public record Page(List<Summary> items, long total, int page, int pageSize) { }
    public record Correction(String status, String correctedAnswer, String note) { }
    public record ExportEntry(String question, String answer) { }
    private record Existing(String id, String status, String payloadHash, long createdAt) { }

    private static final String JOIN = " FROM ai_answer_feedback f JOIN `user` u ON u.id=f.user_id LEFT JOIN tenant t ON t.id=f.tenant_id ";
    private static final String IDENTITIES = "f.id,f.user_id,u.account_id,COALESCE(u.nickname,u.username) AS nickname,f.tenant_id,t.name AS tenant_name,";
    private static final RowMapper<Detail> DETAIL_ROW = (row, index) -> new Detail(row.getString("id"), row.getInt("user_id"),
            row.getString("account_id"), row.getString("nickname"), nullableInt(row, "tenant_id"), row.getString("tenant_name"),
            row.getString("question"), row.getString("answer"), row.getString("page_path"), row.getString("status"),
            row.getString("corrected_answer"), row.getString("note"), "userSubmitted", nullableInt(row, "reviewed_by"),
            row.getLong("created_at"), row.getLong("updated_at"), row.getString("course_id"));

    public Submission submit(int actorId, String clientMessageId, String question, String answer, String pagePath) {
        return submit(actorId, clientMessageId, question, answer, pagePath, null);
    }

    public Submission submit(int actorId, String clientMessageId, String question, String answer, String pagePath, String courseId) {
        User actor = actor(actorId);
        if (actor.getRole() != UserRoleEnum.USER && actor.getRole() != UserRoleEnum.TENANT_ADMIN) forbidden();
        if (actor.getTenantId() == null) forbidden();
        if (clientMessageId == null || !CLIENT_ID.matcher(clientMessageId).matches()) throw new BizException("反馈请求标识不正确");
        String normalizedQuestion = text(question, 2000, true, "问题");
        String normalizedAnswer = text(answer, 12000, true, "回答");
        String normalizedPath = pagePath(pagePath);
        if (courseId != null && (!ID.matcher(courseId).matches() || courses == null || !courses.canRead(actorId, courseId)))
            throw new BizException("课程不存在或无权提交该课程反馈");
        String hash = courseId == null ? hash(normalizedQuestion, normalizedAnswer, normalizedPath)
                : hash(normalizedQuestion, normalizedAnswer, normalizedPath, courseId);
        synchronized (stripes[Math.floorMod(actorId, stripes.length)]) {
            try {
                return transactions.execute(ignored -> submitInside(actorId, actor.getTenantId(), clientMessageId,
                        hash, redact(normalizedQuestion), redact(normalizedAnswer), normalizedPath, courseId));
            } catch (DuplicateKeyException conflict) {
                // The unique (user, client id) constraint is authoritative across instances.
                return transactions.execute(ignored -> submitInside(actorId, actor.getTenantId(), clientMessageId,
                        hash, redact(normalizedQuestion), redact(normalizedAnswer), normalizedPath, courseId));
            }
        }
    }

    private Submission submitInside(int actorId, int tenantId, String clientId, String hash, String question, String answer, String path, String courseId) {
        User current = actor(actorId);
        if ((current.getRole() != UserRoleEnum.USER && current.getRole() != UserRoleEnum.TENANT_ADMIN)
                || !Objects.equals(current.getTenantId(), tenantId)) forbidden();
        if (courseId != null && (courses == null || !courses.canRead(actorId, courseId))) forbidden();
        List<Existing> prior = jdbc.query("SELECT id,status,payload_hash,created_at FROM ai_answer_feedback WHERE user_id=? AND client_message_id=?",
                (row, index) -> new Existing(row.getString("id"), row.getString("status"), row.getString("payload_hash"), row.getLong("created_at")), actorId, clientId);
        if (!prior.isEmpty()) {
            Existing existing = prior.getFirst();
            if (!Objects.equals(existing.payloadHash(), hash)) throw new BizException("同一反馈标识对应的问答已变化，请重新确认后提交");
            return new Submission(existing.id(), existing.status(), existing.createdAt());
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ai_answer_feedback(id,user_id,tenant_id,client_message_id,payload_hash,question,answer,page_path,status,corrected_answer,note,created_at,updated_at,course_id) VALUES (?,?,?,?,?,?,?,?,'OPEN','','',?,?,?)",
                id, actorId, tenantId, clientId, hash, question, answer, path, now, now, courseId);
        return new Submission(id, "OPEN", now);
    }

    public Page list(int actorId, int page, int pageSize, String status) {
        admin(actorId);
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 50) throw new BizException("反馈分页参数不正确");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw new BizException("反馈状态不正确");
        var parameters = new ArrayList<Object>();
        String filter = "";
        if (status != null && !status.isBlank()) { filter = " WHERE f.status=?"; parameters.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_answer_feedback f" + filter, Long.class, parameters.toArray());
        parameters.add(pageSize); parameters.add((page - 1) * pageSize);
        List<Summary> rows = jdbc.query("SELECT " + IDENTITIES + "SUBSTR(f.question,1,160) AS question_preview,f.status,f.created_at,f.updated_at" + JOIN + filter
                        + " ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?", (row, index) -> new Summary(row.getString("id"), row.getInt("user_id"),
                        row.getString("account_id"), row.getString("nickname"), nullableInt(row, "tenant_id"), row.getString("tenant_name"), row.getString("question_preview"),
                        row.getString("status"), "userSubmitted", row.getLong("created_at"), row.getLong("updated_at")), parameters.toArray());
        return new Page(rows, total == null ? 0 : total, page, pageSize);
    }

    public Detail get(int actorId, String id) {
        admin(actorId); validateId(id);
        List<Detail> rows = jdbc.query("SELECT " + IDENTITIES + "f.question,f.answer,f.page_path,f.status,f.corrected_answer,f.note,f.reviewed_by,f.created_at,f.updated_at,f.course_id" + JOIN + " WHERE f.id=?", DETAIL_ROW, id);
        if (rows.isEmpty()) throw new BizException("反馈不存在或已删除");
        return rows.getFirst();
    }

    public Detail review(int actorId, String id, Correction request) {
        admin(actorId); validateId(id);
        if (request == null || request.status() == null || !STATUSES.contains(request.status())) throw new BizException("反馈状态不正确");
        String corrected = text(request.correctedAnswer(), 12000, false, "整理后的回答");
        String note = text(request.note(), 2000, false, "处理备注");
        if ("RESOLVED".equals(request.status()) && corrected.isBlank()) throw new BizException("标记已整理前，请填写正确回答");
        int changed = jdbc.update("UPDATE ai_answer_feedback SET status=?,corrected_answer=?,note=?,reviewed_by=?,updated_at=? WHERE id=?",
                request.status(), redact(corrected), redact(note), actorId, System.currentTimeMillis(), id);
        if (changed == 0) throw new BizException("反馈不存在或已删除");
        return get(actorId, id);
    }

    /** Manual knowledge curation only; no identity metadata, files, model calls or automatic training. */
    public List<ExportEntry> exportResolved(int actorId) {
        admin(actorId);
        return jdbc.query("SELECT question,corrected_answer FROM ai_answer_feedback WHERE status='RESOLVED' AND corrected_answer<>'' ORDER BY updated_at DESC,id DESC LIMIT 500",
                (row, index) -> new ExportEntry(redact(row.getString("question")), redact(row.getString("corrected_answer"))));
    }

    @EventListener
    public void userDeleted(UserDeleteEvent event) {
        jdbc.update("DELETE FROM ai_answer_feedback WHERE user_id=?", event.getId());
        jdbc.update("UPDATE ai_answer_feedback SET reviewed_by=NULL WHERE reviewed_by=?", event.getId());
    }

    private User actor(int actorId) {
        User user = users.requireLoginUser(actorId);
        if (user == null || !Objects.equals(user.getId(), actorId) || actorId <= 2 && actorId != 1
                || !Boolean.TRUE.equals(user.getEnable()) || user.getRole() == null || !user.getRole().isLoginRole()) forbidden();
        return user;
    }
    private void admin(int actorId) { if (actor(actorId).getRole() != UserRoleEnum.ADMIN) forbidden(); }
    private static void forbidden() { throw new ForbiddenAccessException(ErrorCode.NO_FORBIDDEN); }
    private static void validateId(String id) { if (id == null || !ID.matcher(id).matches()) throw new BizException("反馈标识不正确"); }
    private static Integer nullableInt(java.sql.ResultSet row, String column) throws java.sql.SQLException { return row.getObject(column) == null ? null : row.getInt(column); }
    private static String text(String value, int maximum, boolean required, String label) {
        if (value == null) value = "";
        if (value.length() > maximum) throw new BizException(label + "内容过长");
        String result = value.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").strip();
        if (required && result.isBlank()) throw new BizException(label + "不能为空");
        return result;
    }
    private static String pagePath(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > 500 || !value.startsWith("/") || value.startsWith("//") || value.chars().anyMatch(Character::isISOControl))
            throw new BizException("反馈页面路径不正确");
        // Query strings can carry bearer links or tokens, and are not needed for curation.
        return value.split("[?#]", 2)[0];
    }
    static String redact(String value) {
        String result = API_KEY.matcher(value).replaceAll("[已隐藏密钥]");
        result = BEARER.matcher(result).replaceAll("Bearer [已隐藏令牌]");
        return SECRET_VALUE.matcher(result).replaceAll("$1[已隐藏敏感值]");
    }
    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':'); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}

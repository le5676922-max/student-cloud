package io.studentcloud.intelligence.ai;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only finished answers are stored. Feedback uses this record, never client-supplied answer text as authority. */
public final class ChatAnswerStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ChatAnswerStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    public String save(long actorId, Long workspaceId, Long courseId, String question, String answer) {
        return save(actorId,workspaceId,courseId,question,answer,false);
    }
    public String save(long actorId, Long workspaceId, Long courseId, String question, String answer,boolean privateContext) {
        if (actorId < 1 || question == null || question.isBlank() || question.length() > 2000
                || answer == null || answer.isBlank() || answer.length() > 12000) throw invalid();
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO sc_chat_answer(message_id,account_id,workspace_id,course_id,question,answer,private_context,created_at) VALUES(?,?,?,?,?,?,?,?)",
                id, actorId, workspaceId, courseId, question, answer,privateContext, clock.millis());
        return id;
    }
    public void requirePublishable(long feedbackId){
        var flags=jdbc.queryForList("SELECT a.private_context FROM sc_chat_answer a JOIN sc_answer_feedback f ON f.account_id=a.account_id AND f.message_id=a.message_id WHERE f.id=?",Boolean.class,feedbackId);
        if(flags.size()!=1 || !Boolean.FALSE.equals(flags.getFirst()))throw new ApplicationFault("KNOWLEDGE_PRIVATE_SOURCE",403,"含个人资料、附件或私有会话上下文的回答仅供问题处理，不能直接发布到共享知识库");
    }

    public void requireMatch(long actorId, String messageId, String question, String answer, Long courseId) {
        if (messageId == null || !messageId.matches("[a-f0-9]{32}")) throw invalid();
        List<Stored> rows = jdbc.query("SELECT question,answer,course_id FROM sc_chat_answer WHERE account_id=? AND message_id=?",
                (r, i) -> new Stored(r.getString(1), r.getString(2), r.getObject(3, Long.class)), actorId, messageId);
        if (rows.size() != 1 || !rows.getFirst().question.equals(question) || !rows.getFirst().answer.equals(answer)
                || !Objects.equals(rows.getFirst().courseId, courseId)) throw invalid();
    }

    private record Stored(String question, String answer, Long courseId) { }
    private static ApplicationFault invalid() { return new ApplicationFault("ASSISTANT_FEEDBACK_MISMATCH", 409, "请对本轮完整的 AI 回答提交反馈"); }
}

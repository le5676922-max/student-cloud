package io.studentcloud.intelligence;

import io.studentcloud.intelligence.ai.ChatAnswerStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class ChatAnswerStoreTest {
    @Test void bindsFeedbackToOriginalActorQuestionAndAnswer() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:answer_store_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sc_chat_answer (message_id VARCHAR(100) PRIMARY KEY,account_id BIGINT NOT NULL,workspace_id BIGINT,course_id BIGINT,question VARCHAR(2000) NOT NULL,answer TEXT NOT NULL,private_context BOOLEAN NOT NULL DEFAULT TRUE,created_at BIGINT NOT NULL)");
        var store = new ChatAnswerStore(jdbc, Clock.systemUTC());
        String id = store.save(11, 7L, null, "问题", "实际回答");
        store.requireMatch(11, id, "问题", "实际回答", null);
        assertThrows(RuntimeException.class, () -> store.requireMatch(12, id, "问题", "实际回答", null));
        assertThrows(RuntimeException.class, () -> store.requireMatch(11, id, "问题", "伪造回答", null));
    }
    @Test void privateSourceAnswersCannotBecomeSharedKnowledgeThroughAFeedbackReview(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:private_provenance;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sc_chat_answer(message_id VARCHAR(100) PRIMARY KEY,account_id BIGINT,workspace_id BIGINT,course_id BIGINT,question VARCHAR(2000),answer TEXT,private_context BOOLEAN NOT NULL,created_at BIGINT)");
        jdbc.execute("CREATE TABLE sc_answer_feedback(id BIGINT PRIMARY KEY,account_id BIGINT,message_id VARCHAR(100))");
        var store=new ChatAnswerStore(jdbc,Clock.systemUTC());
        String privateId=store.save(3,1L,null,"我的资料","资料摘要",true);
        String publicId=store.save(3,1L,null,"如何上传","点击上传",false);
        jdbc.update("INSERT INTO sc_answer_feedback VALUES(1,3,?)",privateId);jdbc.update("INSERT INTO sc_answer_feedback VALUES(2,3,?)",publicId);
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->store.requirePublishable(1));
        assertDoesNotThrow(()->store.requirePublishable(2));
        assertThrows(io.studentcloud.common.ApplicationFault.class,()->store.requirePublishable(999));
    }
}

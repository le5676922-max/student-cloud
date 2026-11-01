package io.studentcloud.intelligence;

import com.sun.net.httpserver.HttpServer;
import io.studentcloud.account.*;
import io.studentcloud.assistant.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ChatProvenanceTest {
    @Test void optedInTenantKnowledgeRemainsPrivateAfterFeedbackAndHumanReview() throws Exception {
        var database=new JdbcDataSource();database.setURL("jdbc:h2:mem:provenance_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),new ClassPathResource("db/independent-chat-schema.sql"),new ClassPathResource("db/independent-answer-provenance-schema.sql")).execute(database);
        var jdbc=new JdbcTemplate(database);var tx=new DataSourceTransactionManager(database);var clock=Clock.systemUTC();
        Actor admin=new Actor(1,"root","root",null,Role.ADMIN,null),user=new Actor(3,"member","member",null,Role.USER,7L);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:user;}public boolean workspaceEnabled(long id){return id==7;}};
        CourseKnowledgeAccess courses=CourseKnowledgeAccess.unavailable();
        var store=new ChatAnswerStore(jdbc,clock);
        var feedback=new AnswerFeedbackService(jdbc,tx,clock,accounts,courses,store::requirePublishable);
        var knowledge=new ReviewedKnowledgeService(jdbc,tx,clock,accounts,courses,store::requirePublishable);
        String seedId=store.save(user.id(),7L,null,"upload","draft");
        var seed=feedback.submit(user,"upload","draft",seedId);
        var reviewed=feedback.review(admin,seed.id(),1,"upload private workspace instruction",FeedbackStatus.RESOLVED);
        knowledge.publish(admin,seed.id(),reviewed.version(),"upload",KnowledgeScope.TENANT,7L,false);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(body.contains("private workspace instruction"));
            byte[] response="{\"choices\":[{\"message\":{\"content\":\"workspace answer\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try{
            var model=new OpenAiChatClient("http://127.0.0.1:"+server.getAddress().getPort(),"fixture","synthetic-key");
            var chat=new ChatService(accounts,new MemoryService(jdbc,tx,clock,accounts),knowledge,model,store,null,null);
            String question="How does upload work?";
            var answer=chat.answer(user,new ChatService.Request(question,List.of(),"/","concise",null,true,null,null,null));
            assertFalse(answer.knowledge().isEmpty());
            assertTrue(answer.citations().isEmpty());
            var receipt=feedback.submit(user,question,answer.answer(),answer.messageId());
            var resolution=feedback.review(admin,receipt.id(),1,"Reviewed workspace answer",FeedbackStatus.RESOLVED);
            assertEquals(403,assertThrows(ApplicationFault.class,()->knowledge.publish(admin,receipt.id(),resolution.version(),"Should stay private",KnowledgeScope.GLOBAL,null,true)).httpStatus());
            assertTrue(feedback.exportReviewed(admin).stream().noneMatch(item -> item.question().equals(question)),
                    "Human review must not make the private workspace answer exportable");
            assertTrue(feedback.exportReviewed(admin).isEmpty(), "A TENANT publication is not a GLOBAL export");
        }finally{server.stop(0);jdbc.execute("SHUTDOWN");}
    }

    @Test void exportChecksPersistedPublicPrivateUnknownHistoricalAndCourseProvenance() {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:export_provenance_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                new ClassPathResource("db/independent-chat-schema.sql"), new ClassPathResource("db/independent-answer-provenance-schema.sql")).execute(database);
        var jdbc = new JdbcTemplate(database);
        try {
            var tx = new DataSourceTransactionManager(database);
            var clock = Clock.systemUTC();
            Actor admin = new Actor(1, "root", "root", null, Role.ADMIN, null);
            Actor user = new Actor(3, "member", "member", null, Role.USER, 7L);
            AccountDirectory accounts = new AccountDirectory() {
                public Actor account(long id) { return id == 1 ? admin : user; }
                public boolean workspaceEnabled(long id) { return id == 7; }
            };
            var store = new ChatAnswerStore(jdbc, clock);
            var feedback = new AnswerFeedbackService(jdbc, tx, clock, accounts, (actor, courseId) -> {}, store::requirePublishable);
            for (String kind : List.of("public", "memory", "attachment", "history", "unknown", "historical", "course")) {
                Long courseId = kind.equals("course") ? 12L : null;
                String messageId = UUID.randomUUID().toString().replace("-", "");
                if (kind.equals("historical")) {
                    jdbc.update("INSERT INTO sc_chat_answer(message_id,account_id,workspace_id,question,answer,created_at) VALUES(?,3,7,?,'original',?)", messageId, kind, clock.millis());
                } else if (!kind.equals("unknown")) {
                    messageId = store.save(user.id(), 7L, courseId, kind, "original", !kind.equals("public"));
                }
                var receipt = feedback.submit(user, kind, "original", messageId, courseId);
                var reviewed = feedback.review(admin, receipt.id(), 1, "Reviewed " + kind, "INTERNAL NOTE", FeedbackStatus.RESOLVED);
                // Legacy rows intentionally bypass publication validation so export must recheck provenance.
                jdbc.update("INSERT INTO sc_reviewed_knowledge(feedback_id,title,answer,scope,version,source_feedback_version,created_at) VALUES(?,?,?,'GLOBAL',1,?,?)",
                        receipt.id(), "Reviewed " + kind, reviewed.correctedAnswer(), reviewed.version(), reviewed.updatedAt());
            }
            assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer("public", "Reviewed public")), feedback.exportReviewed(admin));
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}

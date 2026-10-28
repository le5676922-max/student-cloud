package io.studentcloud.intelligence;

import com.sun.net.httpserver.HttpServer;
import io.studentcloud.account.*;
import io.studentcloud.assistant.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.OpenAiChatClient;
import io.studentcloud.intelligence.http.AnswerFeedbackController;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackRefinementTest {
 private HttpServer server;private JdbcTemplate jdbc;private AnswerFeedbackService feedback;private AnswerFeedbackController controller;
 private final Actor admin=new Actor(1,"SC1","root",null,Role.ADMIN,null),user=new Actor(3,"SC3","member",null,Role.USER,1L);
 private final AtomicReference<Actor> administrator=new AtomicReference<>(admin);
 private final AtomicReference<String> modelInput=new AtomicReference<>();private final AtomicInteger calls=new AtomicInteger();
 private final AtomicReference<Runnable> duringModel=new AtomicReference<>(()->{});
 private FeedbackView source;
 @BeforeEach void setup()throws Exception{
  var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:refine_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
  new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql")).execute(db);
  jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);
  AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==1?administrator.get():user;}public boolean workspaceEnabled(long id){return id==1;}};
  feedback=new AnswerFeedbackService(jdbc,tx,Clock.systemUTC(),accounts,CourseKnowledgeAccess.unavailable());
  var receipt=feedback.submit(user,"如何使用回收站","INCORRECT ORIGINAL MODEL ANSWER","fixture-refine");source=feedback.detail(admin,receipt.id());
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat/completions",exchange->{
   calls.incrementAndGet();modelInput.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));duringModel.get().run();
   byte[] body="{\"choices\":[{\"message\":{\"content\":\"已整理的草稿，仍需人工审核。\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
  controller=new AnswerFeedbackController(feedback,null,null,new OpenAiChatClient("http://127.0.0.1:"+server.getAddress().getPort(),"fixture","not-a-provider-key"));
 }
 @AfterEach void close(){server.stop(0);jdbc.execute("SHUTDOWN");}
 private AnswerFeedbackController.Refinement input(long timestamp,String draft){return new AnswerFeedbackController.Refinement(String.format("%032x",source.id()),timestamp,draft);}
 private HttpServletRequest request(){return (HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,method,args)->method.getName().equals("getAttribute")&&Actor.class.getName().equals(args[0])?admin:null);}
 @Test void usesExplicitUnsavedHumanCorrectionAndDoesNotPublishOrSaveIt(){
  var result=controller.refine(input(source.updatedAt(),"回收站保留7天，仍占容量，恢复不会覆盖同名文件。"),request()).data();
  assertTrue(modelInput.get().contains("回收站保留7天"));assertFalse(modelInput.get().contains("INCORRECT ORIGINAL"));
  assertEquals(true,result.get("requiresReview"));assertEquals(source.updatedAt(),result.get("sourceUpdatedAt"));
  assertEquals(source.version(),feedback.detail(admin,source.id()).version());assertEquals("",feedback.detail(admin,source.id()).correctedAnswer());
 }
 @Test void staleInputAndEmptyCorrectionAreRejectedBeforeTheExternalCall(){
  assertEquals(409,assertThrows(ApplicationFault.class,()->controller.refine(input(source.updatedAt()-1,"human correction"),request())).httpStatus());
  assertEquals(400,assertThrows(ApplicationFault.class,()->controller.refine(input(source.updatedAt(),"  "),request())).httpStatus());
  assertEquals(0,calls.get());
 }
 @Test void concurrentHumanEditCannotBeReplacedByAnOldModelDraft(){
  duringModel.set(()->feedback.review(admin,source.id(),source.version(),"另一管理员已核对",FeedbackStatus.RESOLVED));
  assertEquals(409,assertThrows(ApplicationFault.class,()->controller.refine(input(source.updatedAt(),"old human correction"),request())).httpStatus());
  assertEquals("另一管理员已核对",feedback.detail(admin,source.id()).correctedAnswer());
 }
 @Test void revokedAdministratorCannotReceiveTheDraft(){
  duringModel.set(()->administrator.set(new Actor(admin.id(),admin.accountId(),admin.username(),null,Role.USER,1L)));
  assertEquals(403,assertThrows(ApplicationFault.class,()->controller.refine(input(source.updatedAt(),"human correction"),request())).httpStatus());
 }
}

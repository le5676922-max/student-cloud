package im.tlgxy.cloud.module.ai.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.config.AiAgentProperties;
import im.tlgxy.cloud.module.ai.config.AiRagProperties;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.service.ProjectKnowledgeService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.course.SharedCourseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Real disposable tables and transactional publications; synthetic questions and mocked AI only. */
class AiKnowledgePublicationTest {
    @TempDir Path directory;
    private final Map<Integer, User> actors = new HashMap<>();
    private JdbcTemplate jdbc;
    private AiFeedbackService feedback;
    private AiKnowledgePublicationService knowledge;
    private AiKnowledgeRefiner refiner;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("knowledge.db"));
        source.setDriverClassName("org.sqlite.JDBC");
        Properties properties = new Properties(); properties.setProperty("foreign_keys", "true"); properties.setProperty("busy_timeout", "10000"); source.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE `user`(id INTEGER PRIMARY KEY,account_id TEXT,username TEXT,nickname TEXT)");
        jdbc.execute("CREATE TABLE tenant(id INTEGER PRIMARY KEY,name TEXT,enable INTEGER)");
        jdbc.update("INSERT INTO tenant VALUES (9,'A',1),(8,'B',1)");
        for (User actor : List.of(user(1, UserRoleEnum.ADMIN, null), user(10, UserRoleEnum.USER, 9), user(11, UserRoleEnum.USER, 8), user(12, UserRoleEnum.TENANT_ADMIN, 9))) {
            actors.put(actor.getId(), actor); jdbc.update("INSERT INTO `user` VALUES (?,?,?,?)", actor.getId(), actor.getAccountId(), actor.getUsername(), actor.getNickname());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V75__ai_answer_feedback.sql"),
                new ClassPathResource("db/migration-sqlite/V78__reviewed_dynamic_knowledge.sql")).execute(source);
        var users = mock(UserService.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            User actor = actors.get(call.getArgument(0));
            if (actor == null || !Boolean.TRUE.equals(actor.getEnable())) throw new BizException("账号不可用");
            return actor;
        });
        var tx = new DataSourceTransactionManager(source);
        feedback = new AiFeedbackService(jdbc, users, tx); refiner = mock(AiKnowledgeRefiner.class);
        knowledge = new AiKnowledgePublicationService(jdbc, users, feedback, refiner, tx);
    }

    @Test void submittedAndRefinedDraftsNeverAutomaticallyEnterKnowledge() {
        var submission = feedback.submit(10, "one", "如何处理资料上传？", "原AI错误回答", null);
        var source = feedback.get(1, submission.id());
        assertTrue(knowledge.visibleDocuments(10, KnowledgeAudience.USER).isEmpty());
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(source, 0, "TENANT", false))));
        assertThrows(BizException.class, () -> knowledge.refine(1, new AiKnowledgePublicationService.RefineRequest(source.id(), source.updatedAt(), " ")));
        when(refiner.refine(anyString(), anyString())).thenReturn("核对后的简洁步骤");
        var preview = knowledge.refine(1, new AiKnowledgePublicationService.RefineRequest(source.id(), source.updatedAt(), "管理员核对后的初稿"));
        assertTrue(preview.requiresReview()); assertEquals("核对后的简洁步骤", preview.answer());
        verify(refiner).refine("如何处理资料上传？", "管理员核对后的初稿");
        assertEquals("", feedback.get(1, source.id()).correctedAnswer());
        assertTrue(knowledge.history(1, source.id()).isEmpty());
    }

    @Test void tenantPublicationIsVisibleOnlyToItsActiveWorkspaceAndMemberAudience() {
        var source = resolved("one", "紫斑藻晶资料怎么整理？", "由课程负责人整理后归档。");
        var result = knowledge.publish(1, batch(entry(source, 0, null, false)));
        assertEquals(1, result.getFirst().version());
        assertEquals(1, knowledge.visibleDocuments(10, KnowledgeAudience.USER).size());
        assertEquals(1, knowledge.visibleDocuments(12, KnowledgeAudience.USER).size());
        assertTrue(knowledge.visibleDocuments(11, KnowledgeAudience.USER).isEmpty());
        assertTrue(knowledge.visibleDocuments(1, KnowledgeAudience.ADMIN).isEmpty());
        assertThrows(BizException.class, () -> knowledge.visibleDocuments(10, KnowledgeAudience.ADMIN));
        jdbc.update("UPDATE tenant SET enable=0 WHERE id=9");
        assertThrows(BizException.class, () -> knowledge.visibleDocuments(10, KnowledgeAudience.USER));
        assertTrue(knowledge.visibleDocuments(11, KnowledgeAudience.USER).isEmpty());
    }

    @Test void onlyExplicitlyConfirmedGeneralKnowledgeCanBecomeGlobalAndCommonPiiIsRedacted() {
        var source = resolved("one", "联系 student@example.invalid 手机13800138000如何上传？", "账号 SC00000000004 可查看上传结果，password=synthetic-secret");
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(source, 0, "GLOBAL", false))));
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(source, 0, "COURSE", false))));
        knowledge.publish(1, batch(entry(source, 0, "GLOBAL", true)));
        var visible = knowledge.visibleDocuments(11, KnowledgeAudience.USER).getFirst();
        assertFalse(visible.getContent().contains("student@example"));
        assertFalse(visible.getContent().contains("13800138000"));
        assertFalse(visible.getContent().contains("SC00000000004"));
        assertFalse(visible.getContent().contains("synthetic-secret"));
        assertEquals(1, knowledge.visibleDocuments(1, KnowledgeAudience.ADMIN).size());
        assertNull(knowledge.history(1, source.id()).getFirst().tenantId());
    }

    @Test void membersAndWorkspaceAdministratorsCannotCurateAndDisabledAdministratorsLoseAccess() {
        var source = resolved("one", "上传问题", "核对后的回答");
        for (int actor : List.of(10, 11, 12)) {
            assertThrows(BizException.class, () -> knowledge.state(actor, source.id()));
            assertThrows(BizException.class, () -> knowledge.history(actor, source.id()));
            assertThrows(BizException.class, () -> knowledge.refine(actor, new AiKnowledgePublicationService.RefineRequest(source.id(), source.updatedAt(), "draft")));
            assertThrows(BizException.class, () -> knowledge.publish(actor, batch(entry(source, 0, "TENANT", false))));
            assertThrows(BizException.class, () -> knowledge.withdraw(actor, new AiKnowledgePublicationService.Withdrawal(source.id(), 1)));
        }
        verifyNoInteractions(refiner);
        actors.get(1).setEnable(false);
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(source, 0, "TENANT", false))));
    }

    @Test void publicationIsAtomicAndRejectsStaleAnswersVersionsDuplicatesAndUnboundedBatches() {
        var a = resolved("one", "问题1", "回答1"); var b = resolved("two", "问题2", "回答2");
        var invalid = new AiKnowledgePublicationService.PublishEntry(b.id(), b.updatedAt(), 0, "问题", "未经保存的回答", "标签", "TENANT", false);
        assertThrows(BizException.class, () -> knowledge.publish(1, new AiKnowledgePublicationService.PublishRequest(List.of(entry(a, 0, "TENANT", false), invalid))));
        assertEquals(0, knowledge.state(1, a.id()).version()); assertEquals(0, knowledge.state(1, b.id()).version());
        var valid = entry(a, 0, "TENANT", false);
        assertThrows(BizException.class, () -> knowledge.publish(1, new AiKnowledgePublicationService.PublishRequest(List.of(valid, valid))));
        assertThrows(BizException.class, () -> knowledge.publish(1, new AiKnowledgePublicationService.PublishRequest(java.util.Collections.nCopies(51, valid))));
        knowledge.publish(1, batch(valid));
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(valid)));
        assertEquals(1, knowledge.history(1, a.id()).size());
        jdbc.update("UPDATE ai_answer_feedback SET updated_at=updated_at+1000 WHERE id=?", b.id());
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(b, 0, "TENANT", false))));
    }

    @Test void withdrawIsImmediateIdempotentAndRepublicationPreservesImmutableHistory() {
        var source = resolved("one", "紫斑藻晶资料整理", "回答第一版");
        knowledge.publish(1, batch(entry(source, 0, "TENANT", false)));
        var withdrawal = new AiKnowledgePublicationService.Withdrawal(source.id(), 1);
        assertEquals("WITHDRAWN", knowledge.withdraw(1, withdrawal).state());
        assertEquals("WITHDRAWN", knowledge.withdraw(1, withdrawal).state());
        assertTrue(knowledge.visibleDocuments(10, KnowledgeAudience.USER).isEmpty());
        source = feedback.review(1, source.id(), new AiFeedbackService.Correction("RESOLVED", "回答第二版", "核对"));
        knowledge.publish(1, batch(entry(source, 1, "TENANT", false)));
        assertEquals(2, knowledge.state(1, source.id()).version());
        var history = knowledge.history(1, source.id());
        assertEquals(List.of("回答第二版", "回答第一版"), history.stream().map(AiKnowledgePublicationService.Revision::answer).toList());
        assertThrows(BizException.class, () -> knowledge.withdraw(1, withdrawal));
    }

    @Test void changedOrDismissedFeedbackImmediatelyInvalidatesItsPreviousPublishedSnapshot() {
        var source = resolved("one", "紫斑藻晶资料整理", "回答第一版");
        knowledge.publish(1, batch(entry(source, 0, "TENANT", false)));
        jdbc.update("UPDATE ai_answer_feedback SET updated_at=updated_at+1000,corrected_answer='回答已更改' WHERE id=?", source.id());
        assertTrue(knowledge.visibleDocuments(10, KnowledgeAudience.USER).isEmpty());
        var current = feedback.get(1, source.id());
        knowledge.publish(1, batch(entry(current, 1, "TENANT", false)));
        assertEquals(1, knowledge.visibleDocuments(10, KnowledgeAudience.USER).size());
        jdbc.update("UPDATE ai_answer_feedback SET status='DISMISSED' WHERE id=?", source.id());
        assertTrue(knowledge.visibleDocuments(10, KnowledgeAudience.USER).isEmpty());
    }

    @Test void realProjectRetrievalConsumesOnlyVisiblePublishedVersionAndNeverWithdrawnText() {
        var source = resolved("one", "紫斑藻晶资料整理", "紫斑藻晶使用蓝色资料夹归档。");
        knowledge.publish(1, batch(entry(source, 0, "TENANT", false)));
        var project = new ProjectKnowledgeService(new AiRagProperties(), new ObjectMapper(), new DefaultResourceLoader());
        project.setDynamicKnowledge(knowledge);
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(10);
            assertTrue(project.search("紫斑藻晶", KnowledgeAudience.USER).stream().anyMatch(row -> row.getId().startsWith("curated-")));
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(11);
            assertTrue(project.search("紫斑藻晶", KnowledgeAudience.USER).stream().noneMatch(row -> row.getId().startsWith("curated-")));
            knowledge.withdraw(1, new AiKnowledgePublicationService.Withdrawal(source.id(), 1));
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(10);
            assertTrue(project.search("紫斑藻晶", KnowledgeAudience.USER).stream().noneMatch(row -> row.getId().startsWith("curated-")));
        }
    }

    @Test void accountDeletionCascadesKnowledgeAndRefinerNeverReceivesOriginalAnswerOrCommonPii() {
        var source = resolved("one", "联系 user@example.invalid 处理上传问题", "正确答案");
        when(refiner.refine(anyString(), anyString())).thenReturn("简洁答案");
        knowledge.refine(1, new AiKnowledgePublicationService.RefineRequest(source.id(), source.updatedAt(), "password=fixture-secret"));
        verify(refiner).refine("联系 [邮箱已隐藏] 处理上传问题", "password=[已隐藏敏感值]");
        knowledge.publish(1, batch(entry(source, 0, "TENANT", false)));
        jdbc.update("DELETE FROM `user` WHERE id=10");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_curated_knowledge", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_curated_knowledge_revision", Long.class));
    }

    @Test void twoConcurrentPublishersCannotOverwriteTheSameExpectedVersion() throws Exception {
        var source = resolved("one", "紫斑藻晶资料整理", "回答");
        AtomicInteger successes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                try { knowledge.publish(1, batch(entry(source, 0, "TENANT", false))); successes.incrementAndGet(); }
                catch (BizException expected) { /* optimistic conflict */ }
            })).toList();
            for (var task : tasks) task.get();
        }
        assertEquals(1, successes.get()); assertEquals(1, knowledge.history(1, source.id()).size());
    }

    @Test void boundedModelTransportRejectsOversizedBodiesAndUnconfiguredOrHttpProvidersWithoutConnecting() {
        var properties = new AiAgentProperties();
        var client = new HttpAiKnowledgeRefiner(properties, new ObjectMapper());
        assertThrows(BizException.class, () -> client.refine("q", "a"));
        properties.setApiKey("synthetic-not-real"); properties.setBaseUrl("http://localhost:1");
        assertThrows(BizException.class, () -> client.refine("q", "a"));
        var subscriber = new HttpAiKnowledgeRefiner.LimitedBodySubscriber();
        var subscription = mock(Flow.Subscription.class); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(262145)));
        verify(subscription).cancel();
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void courseFeedbackCannotBeBroadenedOrReassignedAndOnlyCurrentCourseMembersRetrieveIt() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V79__shared_courses.sql")).execute(jdbc.getDataSource());
        String courseId = "a".repeat(32), otherId = "b".repeat(32);
        jdbc.update("INSERT INTO shared_course(id,owner_user_id,tenant_id,name,description,code_hash,enabled,quota_bytes,used_bytes,version,created_at,updated_at) VALUES (?,12,9,'课程A','',?,1,1000,0,0,1,1)", courseId, "1".repeat(64));
        jdbc.update("INSERT INTO shared_course_member(course_id,user_id,status,upload_status,joined_at,updated_at) VALUES (?,10,'JOINED','NONE',1,1),(?,11,'JOINED','NONE',1,1)", courseId, courseId);
        var courses = mock(SharedCourseService.class);
        when(courses.canRead(anyInt(), anyString())).thenReturn(true);
        when(courses.isActive(anyString())).thenReturn(true);
        feedback.setCourses(courses); knowledge.setCourses(courses);
        var submitted = feedback.submit(10, "course-feedback", "紫斑藻晶课程问题", "旧错误回答", "/courses", courseId);
        assertEquals(courseId, feedback.get(1, submitted.id()).courseId());
        assertEquals(submitted.id(), feedback.submit(10, "course-feedback", "紫斑藻晶课程问题", "旧错误回答", "/courses", courseId).id());
        assertThrows(BizException.class, () -> feedback.submit(10, "course-feedback", "紫斑藻晶课程问题", "旧错误回答", "/courses", otherId));
        var source = feedback.review(1, submitted.id(), new AiFeedbackService.Correction("RESOLVED", "紫斑藻晶只在该课程交流。", ""));
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(source, 0, "TENANT", false))));
        assertThrows(BizException.class, () -> knowledge.publish(1, batch(entry(source, 0, "GLOBAL", true))));
        knowledge.publish(1, batch(entry(source, 0, "COURSE", false)));
        assertEquals(courseId, knowledge.history(1, source.id()).getFirst().courseId());
        assertEquals(1, knowledge.visibleDocuments(11, KnowledgeAudience.USER).size(), "A joined user in another workspace can read course knowledge");
        assertEquals(1, knowledge.visibleDocuments(12, KnowledgeAudience.USER).size(), "The actual creator can read course knowledge");
        actors.put(13, user(13, UserRoleEnum.USER, 9));
        assertTrue(knowledge.visibleDocuments(13, KnowledgeAudience.USER).isEmpty(), "Same workspace does not imply course membership");
        jdbc.update("UPDATE shared_course_member SET status='LEFT' WHERE user_id=11");
        assertTrue(knowledge.visibleDocuments(11, KnowledgeAudience.USER).isEmpty());
        when(courses.canRead(10, courseId)).thenReturn(false);
        assertTrue(knowledge.visibleDocuments(10, KnowledgeAudience.USER).isEmpty(), "Fresh course membership check is authoritative");
        assertThrows(BizException.class, () -> feedback.submit(10, "removed", "问题", "回答", "/courses", courseId));
    }

    private AiFeedbackService.Detail resolved(String client, String question, String answer) {
        var record = feedback.submit(10, client, question, "原回答未经核验", null);
        return feedback.review(1, record.id(), new AiFeedbackService.Correction("RESOLVED", answer, "已人工核对"));
    }
    private static AiKnowledgePublicationService.PublishEntry entry(AiFeedbackService.Detail source, int version, String scope, boolean confirm) {
        return new AiKnowledgePublicationService.PublishEntry(source.id(), source.updatedAt(), version, "整理后的标题", source.correctedAnswer(), "资料整理,课程", scope, confirm);
    }
    private static AiKnowledgePublicationService.PublishRequest batch(AiKnowledgePublicationService.PublishEntry entry) {
        return new AiKnowledgePublicationService.PublishRequest(List.of(entry));
    }
    private static User user(int id, UserRoleEnum role, Integer tenant) {
        var user = new User(); user.setId(id); user.setEnable(true); user.setRole(role); user.setTenantId(tenant); user.setAccountId("SC" + id); user.setUsername("fixture-" + id); return user;
    }
}

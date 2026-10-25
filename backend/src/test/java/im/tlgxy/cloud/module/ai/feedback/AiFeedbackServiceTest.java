package im.tlgxy.cloud.module.ai.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Actual SQLite tables and transactions, synthetic transcripts only; no files, model or real user database. */
class AiFeedbackServiceTest {
    @TempDir Path directory;
    private final Map<Integer, User> actors = new HashMap<>();
    private JdbcTemplate jdbc;
    private AiFeedbackService service;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("feedback.db"));
        source.setDriverClassName("org.sqlite.JDBC");
        Properties properties = new Properties(); properties.setProperty("foreign_keys", "true"); properties.setProperty("busy_timeout", "10000"); source.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE `user` (id INTEGER PRIMARY KEY,account_id TEXT,username TEXT,nickname TEXT)");
        jdbc.execute("CREATE TABLE tenant(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.update("INSERT INTO tenant VALUES(9,'Space A'),(8,'Space B')");
        for (User actor : List.of(user(1, UserRoleEnum.ADMIN, null), user(10, UserRoleEnum.USER, 9), user(11, UserRoleEnum.USER, 8), user(12, UserRoleEnum.TENANT_ADMIN, 9))) {
            actors.put(actor.getId(), actor);
            jdbc.update("INSERT INTO `user` VALUES (?,?,?,?)", actor.getId(), actor.getAccountId(), actor.getUsername(), actor.getNickname());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V75__ai_answer_feedback.sql"),
                new ClassPathResource("db/migration-sqlite/V78__reviewed_dynamic_knowledge.sql")).execute(source);
        UserService users = mock(UserService.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            User user = actors.get(call.getArgument(0));
            if (user == null || !Boolean.TRUE.equals(user.getEnable())) throw new BizException("账号不可用");
            return user;
        });
        service = new AiFeedbackService(jdbc, users, new DataSourceTransactionManager(source));
    }

    @Test void memberAndWorkspaceAdministratorSubmitWhileOnlyGlobalAdministratorCanReadReviewOrExport() {
        var first = service.submit(10, "one", "如何上传？", "旧回答", "/workspace-9?token=ignored");
        service.submit(12, "manager", "本空间设置", "旧回答", null);
        assertEquals(2, service.list(1, 1, 20, null).total());
        assertEquals("OPEN", first.status());
        var detail = service.get(1, first.id());
        assertEquals(10, detail.userId()); assertEquals(9, detail.tenantId());
        assertEquals("userSubmitted", detail.source()); assertEquals("/workspace-9", detail.pagePath());
        for (int user : List.of(10, 11, 12)) {
            assertThrows(BizException.class, () -> service.list(user, 1, 20, null));
            assertThrows(BizException.class, () -> service.get(user, first.id()));
            assertThrows(BizException.class, () -> service.review(user, first.id(), new AiFeedbackService.Correction("DISMISSED", "", "")));
            assertThrows(BizException.class, () -> service.exportResolved(user));
        }
        assertThrows(BizException.class, () -> service.submit(1, "admin", "self", "answer", null));
        actors.get(10).setEnable(false);
        assertThrows(BizException.class, () -> service.submit(10, "disabled", "q", "a", null));
    }

    @Test void theSameUsersClientIdDeduplicatesExactlyAndNeverMergesDifferentPayloadsOrOwners() {
        var first = service.submit(10, "same", "question", "answer", "/learning");
        assertEquals(first, service.submit(10, "same", "question", "answer", "/learning"));
        assertThrows(BizException.class, () -> service.submit(10, "same", "new question", "answer", "/learning"));
        assertThrows(BizException.class, () -> service.submit(10, "same", "question", "new answer", "/learning"));
        var other = service.submit(11, "same", "question", "answer", "/learning");
        assertNotEquals(first.id(), other.id());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_answer_feedback", Long.class));
    }

    @Test void concurrentIdenticalRetriesPersistOnlyOneRecord() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(
                    () -> service.submit(10, "parallel", "question", "answer", null))).toList();
            String id = futures.getFirst().get().id();
            for (var result : futures) assertEquals(id, result.get().id());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_answer_feedback", Long.class));
    }

    @Test void malformedOverlongOrEmptyInputsNeverWrite() {
        for (String client : List.of("", "bad space", "x".repeat(81)))
            assertThrows(BizException.class, () -> service.submit(10, client, "q", "a", null));
        assertThrows(BizException.class, () -> service.submit(10, "empty", " ", "a", null));
        assertThrows(BizException.class, () -> service.submit(10, "empty-a", "q", " ", null));
        assertThrows(BizException.class, () -> service.submit(10, "long-q", "q".repeat(2001), "a", null));
        assertThrows(BizException.class, () -> service.submit(10, "long-a", "q", "a".repeat(12001), null));
        assertThrows(BizException.class, () -> service.submit(10, "path", "q", "a", "https://example.invalid"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_answer_feedback", Long.class));
    }

    @Test void credentialsAreRedactedBeforeStorageButChangedSecretPayloadsStillConflict() {
        var first = service.submit(10, "secrets", "password=fixture-password", "sk-synthetic-secret-0123456789 Bearer fixture-token", "/learning?apiKey=hidden#ignored");
        var detail = service.get(1, first.id());
        assertFalse(detail.question().contains("fixture-password"));
        assertFalse(detail.answer().contains("fixture-token"));
        assertFalse(detail.answer().contains("sk-synthetic-secret"));
        assertEquals("/learning", detail.pagePath());
        assertThrows(BizException.class, () -> service.submit(10, "secrets", "password=different-password", "sk-synthetic-secret-0123456789 Bearer fixture-token", "/learning"));
        assertEquals("密码错误不代表账号不存在", AiFeedbackService.redact("密码错误不代表账号不存在"));
    }

    @Test void reviewRequiresCorrectedAnswerAndExportUsesOnlyResolvedHumanCorrectionsWithoutMetadata() throws Exception {
        var first = service.submit(10, "one", "请问如何处理？", "原始错误回答", "/private-path");
        var second = service.submit(11, "two", "另一个问题", "原始错误回答", null);
        assertThrows(BizException.class, () -> service.review(1, first.id(), new AiFeedbackService.Correction("RESOLVED", " ", "")));
        assertThrows(BizException.class, () -> service.review(1, first.id(), new AiFeedbackService.Correction(null, "", "")));
        assertThrows(BizException.class, () -> service.review(1, first.id(), new AiFeedbackService.Correction("UNKNOWN", "", "")));
        var edited = service.review(1, first.id(), new AiFeedbackService.Correction("RESOLVED", "人工整理后的正确步骤", "复核完成"));
        assertEquals("原始错误回答", edited.answer());
        assertEquals("人工整理后的正确步骤", edited.correctedAnswer()); assertEquals(1, edited.reviewedBy());
        service.review(1, second.id(), new AiFeedbackService.Correction("DISMISSED", "", "重复问题"));
        assertEquals(1, service.list(1, 1, 10, "RESOLVED").total());
        var exported = service.exportResolved(1);
        assertEquals(List.of(new AiFeedbackService.ExportEntry("请问如何处理？", "人工整理后的正确步骤")), exported);
        var json = new ObjectMapper().valueToTree(exported.getFirst());
        var fields = new java.util.HashSet<String>(); json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("question", "answer"), fields);
        assertEquals("RESOLVED", service.submit(10, "one", "请问如何处理？", "原始错误回答", "/private-path").status());
    }

    @Test void pagedInboxesAndExportsStayBoundedAndDoNotIncludeRawAnswersInListProjection() {
        // Bulk seed the volume boundary; ordinary submit/review behavior is covered above.
        new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(ignored -> {
            for (int index = 0; index < 505; index++) jdbc.update(
                    "INSERT INTO ai_answer_feedback(id,user_id,tenant_id,client_message_id,payload_hash,question,answer,page_path,status,corrected_answer,note,created_at,updated_at) VALUES (?,10,9,?,? ,?,'PRIVATE RAW ANSWER','','RESOLVED',?,'',?,?)",
                    String.format("%032x", index + 1), "batch-" + index, "0".repeat(64), "question " + index, "human answer " + index, 1000L + index, 2000L + index);
        });
        assertEquals(505, service.list(1, 1, 50, "RESOLVED").total());
        assertEquals(50, service.list(1, 1, 50, "RESOLVED").items().size());
        assertEquals(5, service.list(1, 11, 50, "RESOLVED").items().size());
        assertEquals(500, service.exportResolved(1).size());
        assertFalse(new ObjectMapper().valueToTree(service.list(1, 1, 20, null)).toString().contains("PRIVATE RAW ANSWER"));
        assertThrows(BizException.class, () -> service.list(1, 0, 20, null));
        assertThrows(BizException.class, () -> service.list(1, 1, 51, null));
    }

    @Test void userDeletionRemovesFeedbackAndRetiredAccountsCannotSubmit() {
        service.submit(10, "one", "question", "answer", null);
        service.submit(11, "two", "question", "answer", null);
        service.userDeleted(new UserDeleteEvent(actors.get(10)));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_answer_feedback", Long.class));
        assertEquals(11, service.list(1, 1, 20, null).items().getFirst().userId());
        assertThrows(BizException.class, () -> service.submit(0, "zero", "q", "a", null));
    }

    @Test void maximumDenseRedactionFitsTheChosenMysqlTextAndMediumtextColumns() {
        String question = "密码=a,".repeat(400); // 2000 UTF-16 chars; short secrets cause the largest common expansion.
        String answer = "密码=a,".repeat(2400); // 12000 UTF-16 chars.
        var submitted = service.submit(10, "capacity", question, answer, null);
        var detail = service.review(1, submitted.id(), new AiFeedbackService.Correction("RESOLVED", answer, question));
        assertTrue(detail.question().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 65_535);
        assertTrue(detail.note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 65_535);
        int answerBytes = detail.answer().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(answerBytes > 65_535, "Original and corrected answers require MEDIUMTEXT rather than TEXT");
        assertTrue(answerBytes < 16_777_215);
        assertTrue(detail.correctedAnswer().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16_777_215);
    }

    private static User user(int id, UserRoleEnum role, Integer tenantId) {
        User user = new User(); user.setId(id); user.setRole(role); user.setTenantId(tenantId); user.setEnable(true);
        user.setAccountId("SC" + id); user.setUsername("synthetic-" + id); user.setNickname("同学" + id); return user;
    }
}

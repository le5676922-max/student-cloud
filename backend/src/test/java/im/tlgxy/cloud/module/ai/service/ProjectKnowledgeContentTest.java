package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.LearningEmbeddingProperties;
import im.tlgxy.cloud.module.ai.config.LearningOcrProperties;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Keep public operational help aligned with the shipped features, not a previous deployment. */
class ProjectKnowledgeContentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PRIVATE_VALUE = Pattern.compile(
            "(?i)\\bsk-[a-z0-9_-]{12,}|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|C:[\\\\/]Users[\\\\/]\\d+");

    private JsonNode canonical(String audience) throws Exception {
        return JSON.readTree(Files.readString(Path.of("scripts/rag/" + audience + "-site-knowledge.json")));
    }

    private JsonNode document(String audience, String suffix) throws Exception {
        for (JsonNode entry : canonical(audience)) {
            if ((audience + "-" + suffix).equals(entry.path("id").asText())) return entry;
        }
        fail("Missing knowledge entry: " + audience + "-" + suffix);
        return null;
    }

    private String text(String audience, String suffix) throws Exception {
        return document(audience, suffix).path("content").asText();
    }

    private void containsAll(String text, String... terms) {
        for (String term : terms) assertTrue(text.contains(term), () -> "Missing fact: " + term + " in " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void generatedResourcesMatchCanonicalAudienceAndContainNoPrivateValues(String audience) throws Exception {
        JsonNode expected = canonical(audience);
        try (var runtime = getClass().getResourceAsStream("/ai/knowledge/" + audience + "-knowledge.json")) {
            assertNotNull(runtime);
            assertEquals(expected, JSON.readTree(runtime));
        }
        assertTrue(expected.size() >= ("user".equals(audience) ? 77 : 113));
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : expected) {
            assertEquals(audience, entry.path("audience").asText());
            assertTrue(entry.path("id").asText().startsWith(audience + "-"));
            assertTrue(ids.add(entry.path("id").asText()), "Duplicate stable knowledge ID");
            assertFalse(entry.path("title").asText().isBlank());
            assertFalse(entry.path("source").asText().isBlank());
            assertTrue(entry.path("tags").isArray() && !entry.path("tags").isEmpty());
            assertFalse(entry.path("content").asText().isBlank());
            assertFalse(PRIVATE_VALUE.matcher(entry.path("content").asText()).find(),
                    () -> "Private value in " + entry.path("id").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void rolesDescribeCurrentLoginRolesNotRetiredGuestAccess(String audience) throws Exception {
        String roles = text(audience, "three-roles");
        for (UserRoleEnum role : UserRoleEnum.values()) {
            if (role.isLoginRole()) assertTrue(roles.contains(role.getValue()));
        }
        assertFalse(roles.contains("GUEST 访客"));
        assertFalse(roles.contains("旧工作空间管理员转为普通用户"));
        containsAll(roles, "全局管理员", "空间管理员", "登录", "长期记忆");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void extractionHelpMatchesLearningLimitsAndDistinguishesOptionalOcr(String audience) throws Exception {
        String formats = text(audience, "learning-formats");
        containsAll(formats, String.format(java.util.Locale.ROOT, "%,d", AiAttachmentService.MAX_LEARNING_CHARS),
                "前 " + AiAttachmentService.MAX_LEARNING_PDF_PAGES + " 页", "5 MB", "30 份", "50 门",
                "默认关闭", "前 " + new LearningOcrProperties().getMaxPages() + " 页", "混合 PDF", "不会自动补识别");
        assertFalse(formats.contains("只保存前 12000"));
        assertFalse(formats.contains("PDF 最多读取前 30 页"));
        containsAll(text(audience, "agent-attachments-privacy"), "12,000", "24,000", "240,000", "不会自动");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void semanticHelpStatesOptInBoundedCoverageAndLexicalFallback(String audience) throws Exception {
        assertFalse(new LearningEmbeddingProperties().isEnabled());
        containsAll(text(audience, "learning-search"), "独立嵌入服务", "RRF", "700", "100", "5 个", "退回关键词",
                "不是训练", "不同资料");
        containsAll(text(audience, "learning-semantic-privacy"), "前 64 段", "700 字符", "1 MiB", "维度", "主动重新索引");
        assertFalse(text(audience, "learning-search").contains("不是语义向量搜索"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void movedFilesHaveOwnerScopedSynchronizationAndExplicitRepairInsteadOfBlindReplay(String audience) throws Exception {
        containsAll(text(audience, "learning-refresh"), "本人的课程索引路径", "不修改其他用户", "修复路径", "不要重复移动");
        containsAll(text(audience, "agent-organize"), "同步本人的课程索引路径", "不要重复执行", "400", "20", "绝不覆盖");
        containsAll(text(audience, "learning-path-repair"), "文件可能已经在新位置", "其他用户", "不会自动同步");
        assertFalse(text(audience, "agent-organize").contains("原有课程索引需重新添加"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void registrationAndRecoveryDoNotInventGlobalAdminOrApprovalBypass(String audience) throws Exception {
        containsAll(text(audience, "registration-admin"), "TENANT_ADMIN", "无需等待人工审核", "SC", "不能注册成全局管理员");
        containsAll(text(audience, "registration-member"), "邀请码", "待审核", "不可登录", "不会获得其他空间权限");
        containsAll(text(audience, "password-reset"), "10 分钟", "60 秒", "5 次", "不能", "绕过", "重新登录");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void explicitMemoryHelpStatesPersistenceControlsAndUntrustedScope(String audience) throws Exception {
        containsAll(text(audience, "long-term-memory"), "最多 " + AiLongTermMemoryService.MAX_ENTRIES + " 条",
                "每条 " + AiLongTermMemoryService.MAX_CHARS + " 字符", "清空聊天", "不会自动保存", "查看我的记忆");
        containsAll(text(audience, "memory-management"), "禁止新增或编辑", "仍可查看或删除", "多窗口", "无法撤回");
        containsAll(text(audience, "memory-privacy"), "不是加密保险箱", "账号之间隔离", "不能授予文件权限");
        containsAll(text(audience, "knowledge-boundaries"), "[K:条目ID]", "[M编号-S片段编号]", "记忆不是引用来源");
    }

    @Test
    void deploymentConfigurationLivesOnlyInAdminHelpAndDoesNotClaimRedisAlreadyEnabled() throws Exception {
        Set<String> privateConfigurationIds = Set.of("embedding-configuration", "ocr-configuration",
                "redis-configuration", "mail-configuration", "workspace-shared-storage");
        for (String suffix : privateConfigurationIds) {
            JsonNode admin = document("admin", suffix);
            assertTrue(Files.isRegularFile(Path.of("..").resolve(admin.path("source").asText())));
            for (JsonNode user : canonical("user")) assertNotEquals("user-" + suffix, user.path("id").asText());
        }
        containsAll(text("admin", "response-latency"), "默认 memory", "不应声称实例已启用 Redis", "不自动");
        containsAll(text("admin", "ocr-configuration"), "默认 false", "Tesseract", "60,000", "混合 PDF");
        containsAll(text("admin", "embedding-configuration"), "默认 false", "HTTPS", "/embeddings", "维度", "不应把分数称为准确率");
        assertFalse(canonical("user").toString().contains("MAIL_PASSWORD"));
    }
}

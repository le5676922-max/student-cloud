package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.AiRagProperties;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class ProjectKnowledgeTest {
    private final ProjectKnowledgeService knowledge = new ProjectKnowledgeService(new AiRagProperties(), new ObjectMapper(), new DefaultResourceLoader());

    @ParameterizedTest
    @CsvSource({
            "怎么把文件存进网盘,user-upload-file",
            "如何创建课程归档学习资料,user-learning-library",
            "邀请成员加入共享课程,user-shared-courses",
            "课程邀请码怎么设置人数和有效期,user-shared-courses",
            "扫描PDF支持OCR吗,user-learning-formats",
            "资料修改后怎么重新索引,user-learning-refresh",
            "回答里的引用片段是PDF页码吗,user-learning-citations",
            "帮我按文件类型分类整理目录,user-agent-organize",
            "把资料批量索引到课程,user-agent-import",
            "任务过期重复确认部分失败怎么办,user-agent-task-status",
            "自动化能越权删除覆盖文件吗,user-agent-automation-security",
            "小芽临时附件分析和上传有什么区别,user-agent-attachments-privacy",
            "响应慢卡顿用了Redis会好吗,user-response-latency",
            "如何更新RAG项目帮助知识库,user-rag-maintenance",
            "ＲＡＧ知识库怎么更新,user-rag-maintenance",
            "邮箱还是账号ID登录,user-login",
            "注册空间管理员还要审核吗,user-registration-admin",
            "邀请码和空间名字加入有什么区别,user-registration-member",
            "忘记密码怎么通过邮箱重置,user-password-reset",
            "请记住我的学习偏好长期保存,user-long-term-memory",
            "关闭长期记忆以后还能删除吗,user-memory-management",
            "长期记忆会改变文件访问权限吗,user-memory-privacy",
            "项目帮助课程资料和长期记忆有什么区别,user-knowledge-boundaries",
            "资料索引概况的部分和未知是什么意思,user-learning-index-scope",
            "语义检索会发送哪些正文给外部服务,user-learning-semantic-privacy",
            "文件已经移动成功但索引同步失败能重试移动吗,user-learning-path-repair"
    })
    void relevantBusinessHelpAppearsInTopFour(String query, String expectedId) {
        var results = knowledge.search(query, KnowledgeAudience.USER);
        assertTrue(results.stream().anyMatch(r -> expectedId.equals(r.getId())), () -> query + " -> " + results.stream().map(r -> r.getId()).toList());
        assertTrue(results.stream().allMatch(r -> r.getAudience().equals("user")));
    }

    @Test void unrelatedQuestionsAreNotGroundedInRandomHelp() {
        assertTrue(knowledge.search("火星轨道偏心率是多少", KnowledgeAudience.USER).isEmpty());
        assertTrue(knowledge.search("你好谢谢", KnowledgeAudience.USER).isEmpty());
        assertTrue(knowledge.search("请问怎么", KnowledgeAudience.USER).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "如何配置独立嵌入模型,admin-embedding-configuration",
            "如何启用Tesseract扫描PDF索引,admin-ocr-configuration",
            "Redis缓存会替代数据库和会话管理吗,admin-redis-configuration",
            "QQ邮箱注册找回密码SMTP配置,admin-mail-configuration",
            "新注册空间管理员如何获得共享云盘,admin-workspace-shared-storage",
            "共享课程邀请成员有效期和人数限制,admin-shared-courses"
    })
    void adminConfigurationHelpAppearsOnlyInAdminResults(String query, String expectedId) {
        var admin = knowledge.search(query, KnowledgeAudience.ADMIN);
        assertTrue(admin.stream().anyMatch(r -> expectedId.equals(r.getId())),
                () -> query + " -> " + admin.stream().map(r -> r.getId()).toList());
        assertTrue(knowledge.search(query, KnowledgeAudience.USER).stream().noneMatch(r -> r.getId().startsWith("admin-")));
    }

    @Test void zeroThresholdStillNeverReturnsZeroEvidenceForPunctuationOrUnrelatedQueries() {
        var properties = new AiRagProperties();
        properties.setMinScore(0);
        var permissive = new ProjectKnowledgeService(properties, new ObjectMapper(), new DefaultResourceLoader());
        assertTrue(permissive.search("!!!???", KnowledgeAudience.USER).isEmpty());
        assertTrue(permissive.search("我", KnowledgeAudience.USER).isEmpty());
        assertTrue(permissive.search("火星轨道偏心率是多少", KnowledgeAudience.USER).isEmpty());
    }

    @Test void onlyEnabledCurrentLoginRolesCanResolveProjectKnowledgeAudience() {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUser).thenReturn(null);
            assertThrows(BizException.class, knowledge::resolveCurrentAudience);
            var user = new User(); user.setEnable(true);
            auth.when(TlgxyCloudAuthUtil::getCurrentUser).thenReturn(user);
            assertThrows(BizException.class, knowledge::resolveCurrentAudience);
            user.setRole(UserRoleEnum.GUEST);
            assertThrows(BizException.class, knowledge::resolveCurrentAudience);
            user.setRole(UserRoleEnum.ADMIN); user.setEnable(false);
            assertThrows(BizException.class, knowledge::resolveCurrentAudience);
            user.setEnable(true);
            assertEquals(KnowledgeAudience.ADMIN, knowledge.resolveCurrentAudience());
            user.setRole(UserRoleEnum.TENANT_ADMIN);
            assertEquals(KnowledgeAudience.USER, knowledge.resolveCurrentAudience());
            user.setRole(UserRoleEnum.USER);
            assertEquals(KnowledgeAudience.USER, knowledge.resolveCurrentAudience());
        }
    }

    @Test void generatedKnowledgeExactlyMatchesCanonicalSources() throws Exception {
        var mapper = new ObjectMapper();
        for (String audience : new String[]{"user", "admin"}) {
            var expected = mapper.readTree(Files.readString(Path.of("scripts/rag/" + audience + "-site-knowledge.json")));
            try (var runtime = getClass().getResourceAsStream("/ai/knowledge/" + audience + "-knowledge.json")) {
                assertNotNull(runtime);
                assertEquals(expected, mapper.readTree(runtime));
                assertTrue(expected.size() >= 60);
            }
        }
    }

    @Test void contextContainsActualSourceReferencesAndRespectsBudget() {
        String context = knowledge.buildContext("分类整理自动化任务", KnowledgeAudience.USER);
        assertTrue(context.contains("[K:user-"));
        assertTrue(context.contains("来源："));
        assertTrue(context.length() <= 5000);
        assertFalse(context.contains("[K:admin-"));
        assertFalse(knowledge.buildContext(knowledge.search("分类整理自动化任务", KnowledgeAudience.ADMIN), KnowledgeAudience.USER).contains("[K:admin-"));
    }
}

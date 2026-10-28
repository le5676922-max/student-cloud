package io.studentcloud.intelligence.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectKnowledgeServiceTest {
    private final Actor user = actor(Role.USER);
    private final Actor administrator = actor(Role.ADMIN);
    private final Actor workspaceAdministrator = actor(Role.TENANT_ADMIN);
    private final ProjectKnowledgeService knowledge = new ProjectKnowledgeService();

    @Test void bundledHelpIsVersionedSubstantiveAndHasIndependentSources() throws Exception {
        try (var input = getClass().getResourceAsStream("/ai/knowledge/project-help.json")) {
            var data = new ObjectMapper().readTree(input);
            assertEquals(1, data.path("schemaVersion").asInt());
            assertEquals("2026-10-27-independent-remote-v2", data.path("revision").asText());
            assertEquals(42, data.path("entries").size());
            var ids = new HashSet<String>();
            for (var entry : data.path("entries")) {
                assertTrue(ids.add(entry.path("id").asText()));
                assertTrue(entry.path("content").asText().length() >= 100, entry.path("id").asText());
                assertTrue(entry.path("source").asText().startsWith("backend-independent/"));
                assertTrue(entry.path("roles").size() > 0);
            }
        }
    }

    @Test void normalChineseAndEnglishQuestionsFindSpecificUsageHelp() {
        assertTrue(knowledge.search(user, "请问上传文件失败怎么办？", 5).stream().anyMatch(hit -> hit.id().equals("file-upload-download")));
        assertTrue(knowledge.search(user, "How do I upload files?", 5).stream().anyMatch(hit -> hit.id().equals("file-upload-download")));
        assertTrue(knowledge.search(user, "个人课程资料更新后如何刷新索引？", 5).stream().anyMatch(hit -> hit.id().equals("learning-refresh-relink")));
        assertTrue(knowledge.search(user, "资料课程".repeat(100) + "如何上传文件", 20).size() <= 20);
        assertTrue(knowledge.search(user, "quantum quasar telescope", 5).isEmpty());
    }

    @Test void administrativeProceduresAreRoleFilteredBeforeReturningHits() {
        assertTrue(knowledge.search(administrator, "反馈审核发布知识库", 20).stream().anyMatch(hit -> hit.id().equals("platform-knowledge-review")));
        assertTrue(knowledge.search(user, "反馈审核发布知识库", 20).stream().noneMatch(hit -> hit.id().startsWith("platform-")));
        assertTrue(knowledge.search(workspaceAdministrator, "空间管理员审核成员重置成员密码", 20).stream().anyMatch(hit -> hit.id().equals("workspace-member-management")));
        assertTrue(knowledge.search(user, "空间管理员审核成员重置成员密码", 20).stream().noneMatch(hit -> hit.id().equals("workspace-member-management")));
    }

    @Test void specificOperationalLimitsAndPrivacyBoundariesRemainTruthful() {
        var upload = find(user, "上传", "file-upload-download");
        assertTrue(upload.content().contains("50MiB"));
        var continuation = find(user, "分片续传", "resumable-upload");
        assertTrue(continuation.content().contains("原内容节点"));
        assertTrue(continuation.content().contains("24小时"));
        var trash = find(user, "回收站", "trash-and-quota");
        assertTrue(trash.content().contains("7天"));
        assertTrue(trash.content().contains("仍计入容量"));
        var share = find(user, "分享", "controlled-file-sharing");
        assertTrue(share.content().contains("单个"));
        assertTrue(share.content().contains("必须登录"));
        assertTrue(share.content().contains("不能分享整个文件夹"));
        var remote = find(administrator, "S3 WebDAV", "remote-storage-not-supported");
        assertTrue(remote.title().contains("WebDAV、FTP、SFTP"));
        assertTrue(remote.content().contains("OSS、S3 兼容存储和 MinIO"));
        assertTrue(remote.content().contains("list、read、mkdir、upload"));
        assertTrue(remote.content().contains("复制、移动、重命名、删除、回收站和分享尚未开放"));
        assertFalse(remote.content().contains("只实现 LOCAL"));
        var coverage = find(user, "课程资料支持", "learning-text-coverage");
        assertTrue(coverage.content().contains("不做图片 OCR"));
        assertTrue(coverage.content().contains("100000字符"));
    }

    @Test void remoteChineseQuestionsFindRoleAppropriateConnectionQuotaAndInspectionHelp() {
        var connection = knowledge.search(workspaceAdministrator, "我是普通管理员，怎么连接阿里云OSS？", 5).getFirst();
        assertEquals("remote-storage-add", connection.id());
        assertTrue(connection.content().contains("API Endpoint"));
        assertTrue(connection.content().contains("本地目录仍需申请"));

        var quota = knowledge.search(user, "远程云盘提示配额不足怎么办？", 5).getFirst();
        assertEquals("remote-member-privacy-quota", quota.id());
        assertTrue(quota.content().contains("/cloud-access"));
        assertTrue(quota.content().contains("空间管理员"));
        assertTrue(quota.content().contains("扫描超出预算"));

        var inspection = knowledge.search(administrator, "全局管理员如何定位未确认写入，UNCERTAIN 或 PUBLISHING 要怎么办？", 5).getFirst();
        assertEquals("remote-pending-write-inspection", inspection.id());
        assertTrue(inspection.content().contains("待核查写入"));
        assertTrue(inspection.content().contains("检查写入结果"));
        assertTrue(inspection.content().contains("不自动轮询"));
        assertTrue(inspection.content().contains("PENDING"));
        assertTrue(inspection.content().contains("不要重复上传"));
    }

    @Test void remoteAdministrationHelpIsRoleFilteredAndNeverClaimsCloudAcceptance() {
        var managementIds = List.of("remote-storage-add", "remote-storage-validation", "remote-storage-write-safety", "remote-pending-write-inspection");
        for (String id : managementIds) {
            assertTrue(knowledge.search(user, "远程存储阿里云连接测试未确认写入", 20).stream().noneMatch(hit -> hit.id().equals(id)));
        }
        assertTrue(knowledge.search(user, "凭据主密钥 SC_STORAGE_MASTER_KEY", 20).stream().noneMatch(hit -> hit.id().equals("remote-storage-credentials")));
        assertTrue(knowledge.search(workspaceAdministrator, "凭据主密钥 SC_STORAGE_MASTER_KEY", 20).stream().noneMatch(hit -> hit.id().equals("remote-storage-credentials")));
        assertNotNull(find(workspaceAdministrator, "未确认写入", "remote-pending-write-inspection"));

        var validation = find(workspaceAdministrator, "只读测试连接", "remote-storage-validation");
        assertTrue(validation.content().contains("单独测试成功不会改变已保存配置"));
        assertTrue(validation.content().contains("同名拒绝"));
        var safety = find(administrator, "OSS 版本控制不覆盖", "remote-storage-write-safety");
        assertTrue(safety.content().contains("Enabled 或 Suspended"));
        assertTrue(safety.content().contains("If-None-Match: *"));
        assertTrue(safety.content().contains("不采用先 HEAD 再覆盖 PUT"));
        var credentials = find(administrator, "SC_STORAGE_MASTER_KEY", "remote-storage-credentials");
        assertTrue(credentials.content().contains("32 个随机字节"));
        assertTrue(credentials.content().contains("不要在聊天"));
        assertTrue(credentials.content().contains("尚未使用真实 OSS/S3 云账号完成验收"));
        assertTrue(credentials.content().contains("不表示已经部署"));
    }

    @Test void rankingIsDeterministicAndRequestBoundsAreEnforced() {
        var first = knowledge.search(user, "分享", 20);
        assertEquals(first, knowledge.search(user, "分享", 20));
        for (int index = 1; index < first.size(); index++) assertTrue(first.get(index - 1).score() >= first.get(index).score());
        assertDoesNotThrow(() -> knowledge.search(user, "如何".repeat(997) + "上传文件", 5));
        assertDoesNotThrow(() -> knowledge.search(user, "上传文件\n如何处理", 5));
        assertEquals(400, assertThrows(ApplicationFault.class, () -> knowledge.search(user, "a".repeat(2001), 5)).httpStatus());
        assertEquals(400, assertThrows(ApplicationFault.class, () -> knowledge.search(user, "上传", 21)).httpStatus());
        assertEquals(401, assertThrows(ApplicationFault.class, () -> knowledge.search(null, "上传", 5)).httpStatus());
    }

    private ProjectKnowledgeService.Hit find(Actor actor, String query, String id) {
        return knowledge.search(actor, query, 20).stream().filter(hit -> hit.id().equals(id)).findFirst().orElseThrow();
    }
    private static Actor actor(Role role) { return new Actor(role.ordinal() + 1, "SC_TEST", "fixture", null, role, role == Role.ADMIN ? null : 7L); }
}

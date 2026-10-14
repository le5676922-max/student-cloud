package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AiAgentToolServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchesAndSummarizesOnlyAccessibleStorage() throws Exception {
        StorageSourceService storageSourceService = mock(StorageSourceService.class);
        UserService userService = mock(UserService.class);
        FileChain fileChain = mock(FileChain.class);
        AbstractBaseFileService<?> fileService = mock(AbstractBaseFileService.class);
        AiAgentToolService service = new AiAgentToolService(storageSourceService, userService, fileChain, null, null, null);

        User user = new User();
        user.setId(4);
        user.setUsername("student");
        user.setEnable(true);

        StorageSource source = new StorageSource();
        source.setId(1);
        source.setKey("LOCAL");
        source.setName("本地存储");

        when(userService.getById(4)).thenReturn(user);
        when(storageSourceService.findAllEnableOrderByOrderNum(4)).thenReturn(List.of(source));
        when(storageSourceService.findIdByKey("LOCAL")).thenReturn(1);
        when(fileService.fileList("/")).thenReturn(List.of(
                file("课程报告.docx", FileTypeEnum.FILE, 2048L),
                file("学习资料", FileTypeEnum.FOLDER, null)
        ));
        when(fileService.fileList("/学习资料/")).thenReturn(List.of(
                file("课程笔记.txt", FileTypeEnum.FILE, 512L)
        ));
        doAnswer(invocation -> invocation.getArgument(0)).when(fileChain).execute(any());

        try (MockedStatic<TlgxyCloudAuthUtil> auth = mockStatic(TlgxyCloudAuthUtil.class);
             MockedStatic<StorageSourceContext> storageContext = mockStatic(StorageSourceContext.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(4);
            storageContext.when(() -> StorageSourceContext.getByStorageId(1)).thenReturn(fileService);

            JsonNode searchArguments = objectMapper.readTree("""
                    {"storageKey":"LOCAL","path":"/","keyword":"课程","maxDepth":2}
                    """);
            Map<?, ?> searchResult = resultMap(service.execute("find_files", searchArguments));
            assertEquals(2, searchResult.get("matchCount"));
            assertEquals(2, searchResult.get("scannedDirectories"));

            JsonNode summaryArguments = objectMapper.readTree("""
                    {"storageKey":"LOCAL","path":"/"}
                    """);
            Map<?, ?> summary = resultMap(service.execute("summarize_directory", summaryArguments));
            assertEquals(1, summary.get("fileCount"));
            assertEquals(1L, summary.get("folderCount"));
            assertEquals(2048L, summary.get("totalFileBytes"));
        }
    }

    @Test
    void rejectsStorageOutsideCurrentUsersAccessibleList() {
        StorageSourceService storageSourceService = mock(StorageSourceService.class);
        UserService userService = mock(UserService.class);
        AiAgentToolService service = new AiAgentToolService(
                storageSourceService,
                userService,
                mock(FileChain.class), null, null, null
        );

        User user = new User();
        user.setId(4);
        user.setEnable(true);
        when(userService.getById(4)).thenReturn(user);
        when(storageSourceService.findAllEnableOrderByOrderNum(4)).thenReturn(List.of());

        try (MockedStatic<TlgxyCloudAuthUtil> auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(4);
            JsonNode arguments = objectMapper.createObjectNode()
                    .put("storageKey", "ADMIN_ONLY")
                    .put("keyword", "secret");

            BizException exception = assertThrows(
                    BizException.class,
                    () -> service.execute("find_files", arguments)
            );
            assertTrue(exception.getMessage().contains("无权访问"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultMap(AiAgentToolService.ToolExecution execution) {
        return (Map<String, Object>) execution.data();
    }

    private FileItemResult file(String name, FileTypeEnum type, Long size) {
        FileItemResult item = new FileItemResult();
        item.setName(name);
        item.setType(type);
        item.setSize(size);
        item.setTime(new Date());
        return item;
    }
}

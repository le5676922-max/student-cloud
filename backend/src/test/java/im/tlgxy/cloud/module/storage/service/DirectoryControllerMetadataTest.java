package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.chain.*;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.controller.file.FileController;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.impl.S3ServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.IntStream;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryControllerMetadataTest {
    private final FileController controller = new FileController();
    private final S3ServiceImpl driver = mock(S3ServiceImpl.class);
    private final FileChain chain = mock(FileChain.class);
    private final DirectoryAccessGuard guard = mock(DirectoryAccessGuard.class);
    DirectoryControllerMetadataTest() throws Exception {
        var sources = mock(StorageSourceService.class); when(sources.findIdByKey("source")).thenReturn(2);
        ReflectionTestUtils.setField(controller, "storageSourceService", sources);
        ReflectionTestUtils.setField(controller, "fileChain", chain);
        ReflectionTestUtils.setField(controller, "directoryMetadataCache", new DirectoryMetadataCache());
        ReflectionTestUtils.setField(controller, "directoryAccessGuard", guard);
        when(driver.getCurrentUserBasePath()).thenReturn("/own");
        when(guard.require(1, 2, "/own")).thenReturn(new DirectoryAccessGuard.Grant("/own", Set.of("download")));
        when(driver.directoryMetadata("/", "/own")).thenAnswer(call -> IntStream.range(0, 10)
                .mapToObj(i -> DirectoryMetadataCacheTest.item("file" + i)).toList());
        when(chain.execute(any(FileContext.class))).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> { List<FileItemResult> page = call.getArgument(0); page.forEach(file -> file.setUrl("synthetic-url")); return null; })
                .when(driver).hydrateDirectoryPage(anyList(), eq("/own"));
    }
    @Test void cursorPagesReuseMetadataButReauthorizeFilterAndSignOnlyTheirOwnFiles() throws Exception {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class); var context = mockStatic(StorageSourceContext.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(1); context.when(() -> StorageSourceContext.getByStorageId(2)).thenReturn(driver);
            var first = controller.list(request(null, false)).getData();
            var second = controller.list(request(first.getNextCursor(), false)).getData();
            assertEquals(2, first.getFiles().size()); assertEquals(2, second.getOffset());
            assertTrue(second.getFiles().stream().allMatch(file -> "synthetic-url".equals(file.getUrl())));
            verify(driver, times(1)).directoryMetadata("/", "/own"); verify(driver, never()).fileList(anyString());
            verify(chain, times(2)).execute(any(FileContext.class)); verify(guard, times(4)).require(1, 2, "/own");
            var pages = org.mockito.ArgumentCaptor.forClass(List.class); verify(driver, times(2)).hydrateDirectoryPage(pages.capture(), eq("/own"));
            assertTrue(pages.getAllValues().stream().allMatch(page -> page.size() == 2));
            controller.list(request(null, true)); verify(driver, times(2)).directoryMetadata("/", "/own");
        }
    }
    @Test void passwordFailureAndRevokedFreshGrantCannotReturnCachedMetadata() throws Exception {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class); var context = mockStatic(StorageSourceContext.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(1); context.when(() -> StorageSourceContext.getByStorageId(2)).thenReturn(driver);
            controller.list(request(null, false));
            when(chain.execute(any(FileContext.class))).thenThrow(new BizException("密码已变化"));
            assertThrows(BizException.class, () -> controller.list(request(null, false)));
            verify(driver, times(1)).hydrateDirectoryPage(anyList(), anyString());
            when(guard.require(1, 2, "/own")).thenThrow(new BizException("授权撤销"));
            assertThrows(BizException.class, () -> controller.list(request(null, false)));
            verify(driver, times(1)).directoryMetadata("/", "/own");
        }
    }
    @Test void readPermissionRemovedByExistingChainDoesNotGenerateAnyDownloadUrls() throws Exception {
        when(chain.execute(any(FileContext.class))).thenAnswer(call -> {
            FileContext context = call.getArgument(0); context.getFileItemList().forEach(file -> file.setUrl(null)); return context;
        });
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class); var context = mockStatic(StorageSourceContext.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(1); context.when(() -> StorageSourceContext.getByStorageId(2)).thenReturn(driver);
            assertTrue(controller.list(request(null, false)).getData().getFiles().stream().allMatch(file -> file.getUrl() == null));
            verify(driver).hydrateDirectoryPage(eq(List.of()), eq("/own"));
        }
    }
    private static FileListRequest request(String cursor, boolean refresh) {
        var request = new FileListRequest(); request.setStorageKey("source"); request.setPath("/"); request.setPageSize(2);
        request.setPageCursor(cursor); request.setRefreshMetadata(refresh); return request;
    }
}

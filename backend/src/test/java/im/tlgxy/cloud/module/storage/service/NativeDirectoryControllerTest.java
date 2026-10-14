package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.controller.file.FileController;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.impl.S3ServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeDirectoryControllerTest {
    private final FileController controller = new FileController();
    private final S3ServiceImpl driver = mock(S3ServiceImpl.class);
    private final FileChain chain = mock(FileChain.class);
    private final DirectoryAccessGuard guard = mock(DirectoryAccessGuard.class);
    private final DirectoryMetadataCache metadataCache = new DirectoryMetadataCache();

    NativeDirectoryControllerTest() throws Exception {
        var sources = mock(StorageSourceService.class); when(sources.findIdByKey("source")).thenReturn(2);
        ReflectionTestUtils.setField(controller, "storageSourceService", sources);
        ReflectionTestUtils.setField(controller, "fileChain", chain);
        ReflectionTestUtils.setField(controller, "directoryMetadataCache", metadataCache);
        ReflectionTestUtils.setField(controller, "directoryAccessGuard", guard);
        ReflectionTestUtils.setField(controller, "nativeDirectoryCursors", new NativeDirectoryCursorService());
        ReflectionTestUtils.setField(controller, "apiRateLimiter", new ApiRateLimiter(null, "test:", false, 100));
        when(driver.supportsNativePagination()).thenReturn(true);
        when(driver.getCurrentUserBasePath()).thenReturn("/own");
        when(guard.require(3, 2, "/own")).thenReturn(new DirectoryAccessGuard.Grant("/own", Set.of("download")));
        when(chain.execute(any(FileContext.class))).thenAnswer(call -> call.getArgument(0));
        when(driver.nativeDirectoryPage(eq("/"), eq("/own"), eq(2), nullable(String.class))).thenAnswer(call ->
                new DirectoryMetadataProvider.NativePage(List.of(DirectoryMetadataCacheTest.item("visible.txt")),
                        call.getArgument(3) == null ? "private-token" : null));
        doAnswer(call -> { List<FileItemResult> page = call.getArgument(0); page.forEach(file -> file.setUrl("signed-current-page")); return null; })
                .when(driver).hydrateDirectoryPage(anyList(), eq("/own"));
    }

    @Test
    void eachPageIsFreshlyAuthorizedAndUsesNoFullEnumerationAndOnlyCurrentPageSigning() throws Exception {
        withContext(() -> {
            var first = controller.list(request(null)).getData();
            assertNull(first.getTotal()); assertFalse(first.isTotalKnown()); assertTrue(first.isNativePagination());
            assertEquals("signed-current-page", first.getFiles().getFirst().getUrl());
            var second = controller.list(request(first.getNextCursor())).getData();
            assertEquals(1, second.getPageIndex()); assertNotNull(second.getPreviousCursor());
            verify(driver).nativeDirectoryPage("/", "/own", 2, null);
            verify(driver).nativeDirectoryPage("/", "/own", 2, "private-token");
            verify(driver, never()).directoryMetadata(anyString(), anyString()); verify(driver, never()).fileList(anyString());
            verify(guard, times(4)).require(3, 2, "/own"); verify(chain, times(4)).execute(any(FileContext.class));
            verify(driver, times(2)).hydrateDirectoryPage(argThat(page -> page.size() == 1), eq("/own"));
        });
    }

    @Test
    void passwordFailureAndRevokedPermissionCannotTriggerRemoteListingOrReuseOldCursor() throws Exception {
        withContext(() -> {
            when(chain.execute(any(FileContext.class))).thenThrow(new BizException("password required"));
            assertThrows(BizException.class, () -> controller.list(request(null)));
            verify(driver, never()).nativeDirectoryPage(anyString(), anyString(), anyInt(), nullable(String.class));
            when(chain.execute(any(FileContext.class))).thenAnswer(call -> call.getArgument(0));
            var first = controller.list(request(null)).getData();
            when(guard.require(3, 2, "/own")).thenThrow(new BizException("membership revoked"));
            assertThrows(BizException.class, () -> controller.list(request(first.getNextCursor())));
            verify(driver, times(1)).nativeDirectoryPage(anyString(), anyString(), anyInt(), nullable(String.class));
        });
    }

    @Test
    void hiddenEmptyPageRetainsNextWhileDownloadRevocationRemovesAllSignedUrls() throws Exception {
        when(chain.execute(any(FileContext.class))).thenAnswer(call -> {
            FileContext context = call.getArgument(0); context.setFileItemList(List.of()); return context;
        });
        withContext(() -> {
            var page = controller.list(request(null)).getData();
            assertTrue(page.getFiles().isEmpty()); assertNotNull(page.getNextCursor());
            verify(driver).hydrateDirectoryPage(eq(List.of()), eq("/own"));
        });
    }

    @Test
    void mutationDuringRemoteIoRejectsPageBeforeSigningOrReturningOldCursor() throws Exception {
        when(driver.nativeDirectoryPage(eq("/"), eq("/own"), eq(2), nullable(String.class))).thenAnswer(call -> {
            metadataCache.invalidate(); return new DirectoryMetadataProvider.NativePage(List.of(DirectoryMetadataCacheTest.item("old.txt")), "next");
        });
        withContext(() -> {
            assertThrows(BizException.class, () -> controller.list(request(null)));
            verify(driver, never()).hydrateDirectoryPage(anyList(), anyString());
        });
    }

    @Test
    void unsupportedDriverIsExplicitAndNeverFallsBackToSlowFullList() throws Exception {
        when(driver.supportsNativePagination()).thenReturn(false);
        withContext(() -> {
            assertTrue(assertThrows(BizException.class, () -> controller.list(request(null))).getMessage().contains("普通浏览"));
            verify(driver, never()).fileList(anyString()); verify(driver, never()).directoryMetadata(anyString(), anyString());
        });
    }

    private FileListRequest request(String cursor) { return NativeDirectoryCursorServiceTest.request(cursor); }
    private void withContext(org.junit.jupiter.api.function.Executable work) throws Exception {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class); var context = mockStatic(StorageSourceContext.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(3);
            context.when(() -> StorageSourceContext.getByStorageId(2)).thenReturn(driver);
            try { work.execute(); } catch (Exception failure) { throw failure; } catch (Throwable failure) { throw new AssertionError(failure); }
        }
    }
}

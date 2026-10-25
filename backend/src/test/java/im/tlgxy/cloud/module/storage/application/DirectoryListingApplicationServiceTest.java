package im.tlgxy.cloud.module.storage.application;

import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.application.port.StorageDriverRegistry;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.DirectoryAccessGuard;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataCache;
import im.tlgxy.cloud.module.storage.service.NativeDirectoryCursorService;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectoryListingApplicationServiceTest {
    private final StorageSourceService sources = mock(StorageSourceService.class);
    private final StorageDriverRegistry drivers = mock(StorageDriverRegistry.class);
    private final DirectoryMetadataCache metadata = mock(DirectoryMetadataCache.class);
    private final DirectoryAccessGuard grants = mock(DirectoryAccessGuard.class);
    private final NativeDirectoryCursorService cursors = mock(NativeDirectoryCursorService.class);
    private final ApiRateLimiter limiter = mock(ApiRateLimiter.class);
    private final FileChain chain = mock(FileChain.class);
    private final DirectoryListingApplicationService listing = new DirectoryListingApplicationService(
            sources, drivers, metadata, grants, cursors, limiter, chain);

    @Test
    void invalidSourceFailsBeforeDriverLookupOrAnyListingWork() {
        // Mockito defaults boxed Integer returns to zero; the real missing-source contract returns null.
        when(sources.findIdByKey("source")).thenReturn(null);
        FileListRequest request = request();
        InvalidStorageSourceBizException failure = assertThrows(InvalidStorageSourceBizException.class,
                () -> listing.list(request, 91));
        assertEquals("source", failure.getStorageKey());
        verify(sources).findIdByKey("source");
        verifyNoInteractions(drivers, metadata, grants, cursors, limiter, chain);
    }

    @Test
    void legacyDriverStillReceivesDefaultsAndReturnsOnlyPolicyProcessedResults() throws Exception {
        AbstractBaseFileService<?> driver = legacyDriver();
        FileItemResult visible = item("visible.txt");
        FileItemResult hidden = item("hidden.txt");
        when(driver.fileList("/")).thenReturn(List.of(visible, hidden));
        FileListRequest request = request();
        when(chain.execute(any(FileContext.class))).thenAnswer(call -> {
            FileContext context = call.getArgument(0);
            assertSame(request, context.getFileListRequest());
            assertSame(driver, context.getFileService());
            assertEquals(2, context.getStorageId());
            assertEquals(List.of(visible, hidden), context.getFileItemList());
            context.setFileItemList(List.of(visible));
            context.setPasswordPattern("protected-folder-pattern");
            return context;
        });

        var page = listing.list(request, 91);

        assertEquals("/", request.getPath());
        assertEquals("name", request.getOrderBy());
        assertEquals("asc", request.getOrderDirection());
        assertSame(visible, page.getFiles().getFirst());
        assertEquals(1, page.getFiles().size());
        assertEquals("protected-folder-pattern", page.getPasswordPattern());
        assertFalse(page.isPaged());
        var order = inOrder(driver, chain);
        order.verify(driver).fileList("/");
        order.verify(chain).execute(any(FileContext.class));
        verifyNoInteractions(metadata, grants, cursors, limiter);
    }

    @Test
    void ordinaryCursorRemainsBoundToTheExplicitActor() throws Exception {
        AbstractBaseFileService<?> driver = legacyDriver();
        when(driver.fileList("/")).thenReturn(List.of(item("course-a.txt"), item("course-b.txt")));
        when(chain.execute(any(FileContext.class))).thenAnswer(call -> call.getArgument(0));
        FileListRequest firstRequest = request();
        firstRequest.setPageSize(1);
        var first = listing.list(firstRequest, 17);
        assertTrue(first.isPaged());
        assertEquals(2, first.getTotal());
        assertNotNull(first.getNextCursor());

        FileListRequest nextRequest = request();
        nextRequest.setPageSize(1);
        nextRequest.setPageCursor(first.getNextCursor());
        assertThrows(BizException.class, () -> listing.list(nextRequest, 18));
        var next = listing.list(nextRequest, 17);
        assertEquals(1, next.getOffset());
        assertEquals("course-b.txt", next.getFiles().getFirst().getName());
        verifyNoInteractions(metadata, grants, cursors, limiter);
    }

    @Test
    void initializedRegistryFailureIsPropagatedWithoutPolicyOrProviderFallback() {
        when(sources.findIdByKey("source")).thenReturn(2);
        InvalidStorageSourceBizException unavailable = new InvalidStorageSourceBizException(2);
        when(drivers.getByStorageId(2)).thenThrow(unavailable);
        FileListRequest request = request();
        assertSame(unavailable, assertThrows(InvalidStorageSourceBizException.class,
                () -> listing.list(request, 91)));
        assertEquals("/", request.getPath());
        verifyNoInteractions(metadata, grants, cursors, limiter, chain);
    }

    private AbstractBaseFileService<?> legacyDriver() {
        AbstractBaseFileService<?> driver = mock(AbstractBaseFileService.class);
        when(sources.findIdByKey("source")).thenReturn(2);
        doReturn(driver).when(drivers).getByStorageId(2);
        when(driver.getCurrentUserBasePath()).thenReturn("/own");
        return driver;
    }

    private static FileListRequest request() {
        var request = new FileListRequest();
        request.setStorageKey("source");
        return request;
    }

    private static FileItemResult item(String name) {
        var item = new FileItemResult();
        item.setName(name);
        item.setPath("/");
        item.setType(FileTypeEnum.FILE);
        item.setSize(12L);
        item.setTime(new Date(1_000));
        return item;
    }
}

package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class NativeDirectoryCursorServiceTest {
    private final NativeDirectoryCursorService service = new NativeDirectoryCursorService();
    private final Object driver = new Object();

    @Test
    void ticketsAreOpaqueAndSupportPreviousPagesWithoutExposingProviderTokensOrTotals() {
        var request = request(null);
        var first = service.resolve(request, driver, 3, "/own", 7);
        var firstPage = service.complete(first, request, driver, 3, "/own", 7, List.of(), null, "private-provider-token");
        assertNull(firstPage.getTotal()); assertFalse(firstPage.isTotalKnown()); assertTrue(firstPage.isNativePagination());
        assertTrue(firstPage.getNextCursor().matches("np_[A-Za-z0-9_-]{43}"));
        assertFalse(firstPage.getNextCursor().contains("private"));
        request.setPageCursor(firstPage.getNextCursor());
        var second = service.resolve(request, driver, 3, "/own", 7);
        assertEquals("private-provider-token", second.providerToken());
        var secondPage = service.complete(second, request, driver, 3, "/own", 7, List.of(), null, null);
        assertEquals(1, secondPage.getPageIndex()); assertEquals(first.cursor(), secondPage.getPreviousCursor());
        request.setPageCursor(secondPage.getPreviousCursor());
        assertNull(service.resolve(request, driver, 3, "/own", 7).providerToken());
        assertEquals(firstPage.getNextCursor(), service.complete(first, request, driver, 3, "/own", 7,
                List.of(), null, "private-provider-token").getNextCursor());
    }

    @Test
    void cursorCannotMoveAcrossActorRootStorageDirectoryDriverPageSizeOrMutationGeneration() {
        var request = request(null); var first = service.resolve(request, driver, 3, "/own", 7);
        request.setPageCursor(first.cursor());
        assertChanged(() -> service.resolve(request, driver, 4, "/own", 7));
        assertChanged(() -> service.resolve(request, driver, 3, "/other", 7));
        assertChanged(() -> service.resolve(request, new Object(), 3, "/own", 7));
        assertChanged(() -> service.resolve(request, driver, 3, "/own", 8));
        request.setStorageKey("different"); assertChanged(() -> service.resolve(request, driver, 3, "/own", 7));
        request.setStorageKey("source"); request.setPath("/other/"); assertChanged(() -> service.resolve(request, driver, 3, "/own", 7));
        request.setPath("/"); request.setPageSize(3); assertChanged(() -> service.resolve(request, driver, 3, "/own", 7));
        request.setPageSize(2); request.setPageCursor("opaque-cloud-provider-token");
        assertChanged(() -> service.resolve(request, driver, 3, "/own", 7));
    }

    @Test
    void everyPageHiddenStillRetainsNextAndLoopingProviderTokenIsRejected() {
        var request = request(null); var first = service.resolve(request, driver, 3, "/own", 7);
        var page = service.complete(first, request, driver, 3, "/own", 7, List.of(), null, "A");
        assertNotNull(page.getNextCursor()); assertTrue(page.getFiles().isEmpty());
        request.setPageCursor(page.getNextCursor()); var second = service.resolve(request, driver, 3, "/own", 7);
        assertThrows(BizException.class, () -> service.complete(second, request, driver, 3, "/own", 7, List.of(), null, "A"));
        var next = service.complete(second, request, driver, 3, "/own", 7, List.of(), null, "B");
        request.setPageCursor(next.getNextCursor()); var third = service.resolve(request, driver, 3, "/own", 7);
        assertThrows(BizException.class, () -> service.complete(third, request, driver, 3, "/own", 7, List.of(), null, "A"));
    }

    @Test
    void expiryDoesNotSlideAndInFlightMutationCannotCompleteOldTicket() {
        var clock = new AtomicLong(0);
        var isolated = new NativeDirectoryCursorService(Duration.ofSeconds(1), clock::get);
        var request = request(null); var first = isolated.resolve(request, driver, 3, "/own", 7);
        assertChanged(() -> isolated.complete(first, request, driver, 3, "/own", 8, List.of(), null, null));
        request.setPageCursor(first.cursor()); clock.set(999_999_999);
        assertEquals(first.cursor(), isolated.resolve(request, driver, 3, "/own", 7).cursor());
        clock.set(1_000_000_000);
        assertChanged(() -> isolated.resolve(request, driver, 3, "/own", 7));
    }

    @Test
    void serverMemoryTokenBudgetIsBoundedAndGlobalSortOrSearchAreNeverPretended() {
        var request = request(null); var current = service.resolve(request, driver, 3, "/own", 7);
        for (int index = 0; index < 128; index++) {
            String token = Integer.toString(index) + "x".repeat(8185);
            var page = service.complete(current, request, driver, 3, "/own", 7, List.of(), null, token);
            request.setPageCursor(page.getNextCursor()); current = service.resolve(request, driver, 3, "/own", 7);
        }
        var full = current;
        assertThrows(BizException.class, () -> service.complete(full, request, driver, 3, "/own", 7,
                List.of(), null, "overflow" + "x".repeat(8184)));
        request.setPageCursor(null); request.setOrderBy("name");
        assertThrows(BizException.class, () -> service.resolve(request, driver, 3, "/own", 7));
        request.setOrderBy(null); request.setNameQuery("keyword");
        assertThrows(BizException.class, () -> service.resolve(request, driver, 3, "/own", 7));
        request.setNameQuery(null); request.setPageSize(201);
        assertThrows(BizException.class, () -> service.resolve(request, driver, 3, "/own", 7));
    }

    static FileListRequest request(String cursor) {
        var request = new FileListRequest(); request.setStorageKey("source"); request.setPath("/");
        request.setPageSize(2); request.setNativePagination(true); request.setPageCursor(cursor); return request;
    }
    private void assertChanged(org.junit.jupiter.api.function.Executable action) {
        assertEquals("41040", assertThrows(BizException.class, action).getCode());
    }
}

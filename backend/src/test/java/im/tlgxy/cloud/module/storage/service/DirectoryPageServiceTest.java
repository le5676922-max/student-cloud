package im.tlgxy.cloud.module.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryPageServiceTest {
    @Test void legacyResponseIsUnpagedAndUnchanged() {
        var files = files(600);
        var request = request(null);
        var result = page(files, request);
        assertFalse(result.isPaged()); assertEquals(files, result.getFiles()); assertEquals(600, result.getTotal());
        assertNull(result.getNextCursor());
    }

    @Test void forwardBackwardAndLastPageDoNotDuplicateOrOmitFiles() {
        var files = files(507);
        var request = request(200);
        var first = page(files, request);
        assertEquals(files.subList(0, 200), first.getFiles()); assertNull(first.getPreviousCursor());
        request.setPageCursor(first.getNextCursor());
        var second = page(files, request);
        assertEquals(200, second.getOffset()); assertEquals(files.subList(200, 400), second.getFiles());
        request.setPageCursor(second.getNextCursor());
        var third = page(files, request);
        assertEquals(files.subList(400, 507), third.getFiles()); assertNull(third.getNextCursor());
        request.setPageCursor(second.getPreviousCursor());
        assertEquals(first, page(files, request));
    }

    @Test void filenameFilterRunsAcrossTheWholeAuthorizedDirectoryBeforePaging() {
        var files = files(1000);
        var request = request(10);
        request.setNameQuery(" FILE-999.TXT ");
        var result = page(files, request);
        assertEquals(1, result.getTotal()); assertEquals("file-999.txt", result.getFiles().getFirst().getName());
        request.setNameQuery("missing");
        result = page(files, request);
        assertEquals(0, result.getTotal()); assertTrue(result.getFiles().isEmpty()); assertNull(result.getNextCursor());
    }

    @Test void directoryModificationAndUrlPermissionChangesInvalidateCursorButSignedUrlRefreshDoesNot() {
        var files = files(30);
        var request = request(10);
        request.setPageCursor(page(files, request).getNextCursor());
        files.forEach(file -> file.setUrl("https://storage.test/file?new-signature"));
        assertEquals(10, page(files, request).getOffset());
        files.getLast().setSize(999L);
        assertEquals("41040", assertThrows(BizException.class, () -> page(files, request)).getCode());
        files.getLast().setSize(1L);
        files.getLast().setUrl(null);
        assertEquals("41040", assertThrows(BizException.class, () -> page(files, request)).getCode());
    }

    @Test void cursorCannotBeReusedForAnotherAccountRootSortOrFilter() {
        var files = files(30);
        var request = request(10);
        request.setPageCursor(page(files, request).getNextCursor());
        assertThrows(BizException.class, () -> DirectoryPageService.page(files, "/protected/**", request, 8, "/private/"));
        assertThrows(BizException.class, () -> DirectoryPageService.page(files, "/protected/**", request, 7, "/other/"));
        request.setOrderDirection("desc");
        assertThrows(BizException.class, () -> page(files, request));
        request.setOrderDirection("asc"); request.setNameQuery("file");
        assertThrows(BizException.class, () -> page(files, request));
    }

    @ParameterizedTest @ValueSource(strings = {"bad", "a:0", "../secret", "0"})
    void malformedCursorIsRejected(String cursor) {
        var request = request(10); request.setPageCursor(cursor);
        assertThrows(BizException.class, () -> page(files(20), request));
    }

    @Test void invalidPageSizeAndOutOfRangeCursorAreRejected() {
        for (int size : new int[]{-1, 0, 501}) assertThrows(BizException.class, () -> page(files(10), request(size)));
        var request = request(5); var result = page(files(10), request);
        String revision = result.getNextCursor().substring(0, 65);
        for (String offset : List.of("10", "2147483648", "9999999999")) {
            request.setPageCursor(revision + offset);
            assertThrows(BizException.class, () -> page(files(10), request));
        }
    }

    @Test void tenThousandItemResponseIsBoundedWithoutCachingPrivateData() throws Exception {
        var files = files(10000);
        var mapper = new ObjectMapper();
        int oldBytes = mapper.writeValueAsBytes(page(files, request(null))).length;
        var result = page(files, request(200));
        int pagedBytes = mapper.writeValueAsBytes(result).length;
        assertEquals(10000, result.getTotal()); assertEquals(200, result.getFiles().size());
        assertTrue(pagedBytes < oldBytes / 20);
        System.out.printf("Directory payload sample: total=10000, returned=200, full=%d bytes, paged=%d bytes%n", oldBytes, pagedBytes);
    }

    private static FileListRequest request(Integer size) {
        var request = new FileListRequest(); request.setStorageKey("local"); request.setPath("/docs/");
        request.setOrderBy("name"); request.setOrderDirection("asc"); request.setPageSize(size); return request;
    }
    private static FileInfoResult page(List<FileItemResult> files, FileListRequest request) {
        return DirectoryPageService.page(files, "/protected/**", request, 7, "/private/");
    }
    private static List<FileItemResult> files(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            var file = new FileItemResult(); file.setName("file-" + i + ".txt"); file.setPath("/docs/");
            file.setType(FileTypeEnum.FILE); file.setSize(1L); file.setTime(new Date(100));
            file.setUrl("https://storage.test/file?signature"); return file;
        }).toList();
    }
}

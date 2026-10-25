package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.password.model.dto.VerifyResultDTO;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.command.FileAccessPermissionVerifyCommand;
import im.tlgxy.cloud.module.storage.chain.command.FileHiddenCommand;
import im.tlgxy.cloud.module.storage.chain.command.FileSortCommand;
import im.tlgxy.cloud.module.storage.chain.command.FolderPasswordVerifyCommand;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.DirectoryAccessGuard;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataProvider.NativePage;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.impl.S3ServiceImpl;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real permission/password/hidden/sort chain, synthetic provider only: no database, cloud or model calls. */
class AiAgentNativeDirectoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final S3ServiceImpl driver = mock(S3ServiceImpl.class);
    private final DirectoryAccessGuard guard = mock(DirectoryAccessGuard.class);
    private final FilterConfigService filters = mock(FilterConfigService.class);
    private final PasswordConfigService passwords = mock(PasswordConfigService.class);
    private final AiAgentToolService service;

    AiAgentNativeDirectoryTest() {
        var users = mock(UserService.class);
        User user = new User(); user.setId(4); user.setEnable(true);
        when(users.getById(4)).thenReturn(user);
        var sources = mock(StorageSourceService.class);
        var source = new StorageSource(); source.setId(2); source.setKey("S3"); source.setName("测试存储");
        when(sources.findAllEnableOrderByOrderNum(4)).thenReturn(List.of(source));
        when(sources.findIdByKey("S3")).thenReturn(2);
        when(driver.supportsNativePagination()).thenReturn(true);
        when(driver.getCurrentUserBasePath()).thenReturn("/own");
        when(guard.require(4, 2, "/own")).thenReturn(new DirectoryAccessGuard.Grant("/own", Set.of("preview")));
        when(passwords.verifyPassword(eq(2), anyString(), isNull())).thenReturn(VerifyResultDTO.success());
        var chain = new FileChain();
        var access = new FileAccessPermissionVerifyCommand();
        ReflectionTestUtils.setField(access, "filterConfigService", filters);
        var password = new FolderPasswordVerifyCommand();
        ReflectionTestUtils.setField(password, "passwordConfigService", passwords);
        ReflectionTestUtils.setField(password, "userStorageSourceService", mock(UserStorageSourceService.class));
        var hidden = new FileHiddenCommand();
        ReflectionTestUtils.setField(hidden, "filterConfigService", filters);
        chain.addCommand(access); chain.addCommand(password); chain.addCommand(hidden); chain.addCommand(new FileSortCommand());
        service = new AiAgentToolService(sources, users, chain, null, null, null, guard);
    }

    @Test void largeDirectoryIsBoundedBeforeEnumerationOrSigningAndSortIsExplicitlyPartial() throws Exception {
        AtomicInteger pages = endlessFullPages();
        withContext(() -> {
            Map<?, ?> data = data("list_directory", "{\"orderBy\":\"size\",\"orderDirection\":\"desc\"}");
            assertEquals(2, pages.get()); assertEquals(400, data.get("scannedItems"));
            assertEquals(400, data.get("observedCount")); assertEquals(60, ((List<?>) data.get("items")).size());
            assertNull(data.get("total")); assertEquals(false, data.get("complete"));
            assertEquals(true, data.get("truncated")); assertEquals("SCANNED_ITEMS_ONLY", data.get("sortScope"));
            assertEquals(true, data.get("ioBounded")); assertEquals("NATIVE_BOUNDED", data.get("scanMode"));
            assertEquals(399L, ((Map<?, ?>) ((List<?>) data.get("items")).getFirst()).get("size"));
        });
    }

    @Test void completedSmallDirectoryRetainsRequestedSortAndRealHiddenRules() throws Exception {
        when(filters.checkFileIsHidden(2, "/hidden.txt")).thenReturn(true);
        when(driver.nativeDirectoryPage(eq("/"), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call ->
                call.getArgument(3) == null
                        ? new NativePage(List.of(file("b.txt", 2), file("hidden.txt", 9)), "second")
                        : new NativePage(List.of(file("a.txt", 1)), null));
        withContext(() -> {
            Map<?, ?> data = data("list_directory", "{\"orderDirection\":\"desc\"}");
            assertEquals(true, data.get("complete")); assertEquals(2, data.get("total"));
            assertEquals(3, data.get("scannedItems")); assertEquals("FULL_DIRECTORY", data.get("sortScope"));
            assertEquals(List.of("b.txt", "a.txt"), ((List<?>) data.get("items")).stream()
                    .map(item -> ((Map<?, ?>) item).get("name")).toList());
            assertFalse(json.writeValueAsString(data).contains("second"), "Provider tokens must remain server-only");
        });
    }

    @Test void statisticsAndLargestFilesAreClearlyLimitedToScannedSubset() throws Exception {
        endlessFullPages();
        withContext(() -> {
            var execution = execute("summarize_directory", "{}");
            Map<?, ?> data = (Map<?, ?>) execution.data();
            assertEquals(400, data.get("fileCount")); assertEquals(false, data.get("complete"));
            assertEquals("SCANNED_ITEMS_ONLY", data.get("statisticsScope"));
            assertTrue(execution.summary().contains("不是全目录统计"));
        });
    }

    @Test void recursiveSearchSharesOneRawItemBudgetAcrossDirectories() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            String path = call.getArgument(0); int size = call.getArgument(2);
            if (path.equals("/")) { var folder = file("child", 0); folder.setType(FileTypeEnum.FOLDER); return new NativePage(List.of(folder), null); }
            assertEquals("/child/", path);
            return new NativePage(IntStream.range(0, size).mapToObj(i -> file("unmatched-" + i + ".txt", i)).toList(), "next-" + size);
        });
        withContext(() -> {
            Map<?, ?> data = data("find_files", "{\"keyword\":\"missing\",\"maxDepth\":3}");
            assertEquals(400, data.get("providerScannedItems")); assertEquals(3, data.get("providerRequests"));
            assertEquals(0, data.get("matchCount")); assertEquals(true, data.get("truncated"));
            assertEquals(false, data.get("complete"));
        });
    }

    @Test void endlessEmptyPagesCannotExhaustServerOrPretendDirectoryIsEmpty() throws Exception {
        AtomicInteger count = new AtomicInteger();
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenAnswer(call -> new NativePage(List.of(), "empty-" + count.incrementAndGet()));
        withContext(() -> {
            Map<?, ?> data = data("list_directory", "{}");
            assertEquals(4, count.get()); assertEquals(0, data.get("scannedItems"));
            assertNull(data.get("total")); assertEquals(false, data.get("complete"));
            assertEquals(true, data.get("truncated"));
        });
    }

    @Test void providerPlaceholderEntriesConsumeRawBudgetEvenWhenNoFilesAreReturned() throws Exception {
        AtomicInteger count = new AtomicInteger();
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenAnswer(call -> new NativePage(List.of(), "placeholder-" + count.incrementAndGet(), call.getArgument(2)));
        withContext(() -> {
            Map<?, ?> data = data("list_directory", "{}");
            assertEquals(2, count.get()); assertEquals(400, data.get("scannedItems"));
            assertEquals(0, data.get("observedCount")); assertNull(data.get("total"));
            assertEquals(false, data.get("complete"));
        });
    }

    @Test void recursiveSearchSharesRequestBudgetIncludingManyTinyDirectories() throws Exception {
        AtomicInteger count = new AtomicInteger();
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            count.incrementAndGet();
            if (call.getArgument(0).equals("/")) return new NativePage(IntStream.range(0, 10).mapToObj(i -> {
                var folder = file("child-" + i, 0); folder.setType(FileTypeEnum.FOLDER); return folder;
            }).toList(), null);
            return new NativePage(List.of(), null);
        });
        withContext(() -> {
            Map<?, ?> data = data("find_files", "{\"keyword\":\"missing\",\"maxDepth\":3}");
            assertEquals(4, count.get()); assertEquals(4, data.get("providerRequests"));
            assertEquals(10, data.get("providerScannedItems")); assertEquals(true, data.get("truncated"));
            assertNull(data.get("total"));
        });
    }

    @Test void fullyHiddenPagesConsumeBudgetAndNeverLeakNamesOrFalseTotals() throws Exception {
        AtomicInteger count = new AtomicInteger();
        when(filters.checkFileIsHidden(eq(2), anyString())).thenReturn(true);
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenAnswer(call -> new NativePage(List.of(file("secret.txt", 1)), "hidden-" + count.incrementAndGet()));
        withContext(() -> {
            Map<?, ?> data = data("list_directory", "{}");
            assertEquals(4, count.get()); assertEquals(4, data.get("scannedItems"));
            assertEquals(0, data.get("observedCount")); assertNull(data.get("total"));
            assertFalse(json.writeValueAsString(data).contains("secret.txt"));
        });
    }

    @Test void realPasswordFailureStopsBeforeAnyProviderIo() throws Exception {
        when(passwords.verifyPassword(eq(2), anyString(), isNull())).thenReturn(VerifyResultDTO.fail(ErrorCode.BIZ_BAD_REQUEST));
        withContext(() -> assertThrows(BizException.class, () -> execute("list_directory", "{}")));
        verify(driver, never()).nativeDirectoryPage(anyString(), anyString(), anyInt(), nullable(String.class));
    }

    @Test void inaccessibleDirectoryStopsBeforeAnyProviderIo() throws Exception {
        when(filters.checkFileIsInaccessible(2, "/")).thenReturn(true);
        withContext(() -> assertThrows(BizException.class, () -> execute("list_directory", "{}")));
        verify(driver, never()).nativeDirectoryPage(anyString(), anyString(), anyInt(), nullable(String.class));
    }

    @Test void changedGrantDuringRemoteIoCannotReturnAnyMetadata() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            when(guard.require(4, 2, "/own")).thenReturn(new DirectoryAccessGuard.Grant("/other", Set.of("preview")));
            return new NativePage(List.of(file("old-private.txt", 1)), null);
        });
        withContext(() -> assertTrue(assertThrows(BizException.class, () -> execute("list_directory", "{}"))
                .getMessage().contains("授权已变化")));
    }

    @Test void grantRevocationDuringAChildSearchCannotReturnPreviouslyAcceptedParentMatches() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            if (call.getArgument(0).equals("/")) {
                var folder = file("child", 0); folder.setType(FileTypeEnum.FOLDER);
                return new NativePage(List.of(file("matched-private.txt", 1), folder), null);
            }
            when(guard.require(4, 2, "/own")).thenThrow(new BizException("membership revoked"));
            return new NativePage(List.of(), null);
        });
        withContext(() -> assertThrows(BizException.class,
                () -> execute("find_files", "{\"keyword\":\"matched\",\"maxDepth\":2}")));
    }

    @Test void passwordOrDirectoryRuleAddedDuringRemoteIoPreventsMetadataReturning() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            when(passwords.verifyPassword(eq(2), anyString(), isNull()))
                    .thenReturn(VerifyResultDTO.fail(ErrorCode.BIZ_BAD_REQUEST));
            return new NativePage(List.of(file("private.txt", 1)), null);
        });
        withContext(() -> assertThrows(BizException.class, () -> execute("list_directory", "{}")));
        verify(passwords, atLeast(2)).verifyPassword(eq(2), anyString(), isNull());
    }

    @Test void hiddenRulesChangedDuringRemoteIoFilterAllCollectedMetadata() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            when(filters.checkFileIsHidden(eq(2), anyString())).thenReturn(true);
            return new NativePage(List.of(file("now-hidden.txt", 1)), null);
        });
        withContext(() -> {
            var data = data("list_directory", "{}");
            assertEquals(true, data.get("complete")); assertEquals(0, data.get("total"));
            assertEquals(1, data.get("scannedItems")); assertFalse(json.writeValueAsString(data).contains("now-hidden"));
        });
    }

    @Test void providerFailureNeverFallsBackToFullEnumeration() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenThrow(new BizException("Synthetic provider unavailable"));
        withContext(() -> assertThrows(BizException.class, () -> execute("list_directory", "{}")));
    }

    @Test void childProviderFailureAbortsInsteadOfSilentlySpendingMoreUnknownIoBudget() throws Exception {
        AtomicInteger count = new AtomicInteger();
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            if (count.incrementAndGet() == 1) return new NativePage(IntStream.range(0, 2).mapToObj(i -> {
                var folder = file("child-" + i, 0); folder.setType(FileTypeEnum.FOLDER); return folder;
            }).toList(), null);
            throw new IllegalStateException("Synthetic timeout");
        });
        withContext(() -> assertThrows(BizException.class, () -> execute("find_files", "{\"keyword\":\"missing\"}")));
        assertEquals(2, count.get());
    }

    @Test void nullableOrInvalidRawCountPageFailsClosed() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenReturn(null);
        withContext(() -> assertThrows(BizException.class, () -> execute("list_directory", "{}")));
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenReturn(new NativePage(List.of(file("f.txt", 1)), null, 0));
        withContext(() -> assertThrows(BizException.class, () -> execute("list_directory", "{}")));
    }

    @Test void repeatedTokensAndOversizedProviderPagesFailClosed() throws Exception {
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenReturn(new NativePage(List.of(), "same"));
        withContext(() -> assertTrue(assertThrows(BizException.class, () -> execute("list_directory", "{}"))
                .getMessage().contains("游标异常")));
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class)))
                .thenReturn(new NativePage(IntStream.range(0, 201).mapToObj(i -> file("f" + i, i)).toList(), null));
        withContext(() -> assertTrue(assertThrows(BizException.class, () -> execute("list_directory", "{}"))
                .getMessage().contains("分页响应异常")));
    }

    @Test void missingDetailOutsideBoundedScanIsNotClaimedNonexistent() throws Exception {
        endlessFullPages();
        withContext(() -> assertTrue(assertThrows(BizException.class,
                () -> execute("get_file_details", "{\"path\":\"/not-in-first-400.txt\"}"))
                .getMessage().contains("不能据此判断文件不存在")));
    }

    private AtomicInteger endlessFullPages() {
        AtomicInteger count = new AtomicInteger();
        when(driver.nativeDirectoryPage(anyString(), eq("/own"), anyInt(), nullable(String.class))).thenAnswer(call -> {
            int page = count.getAndIncrement(); int size = call.getArgument(2);
            return new NativePage(IntStream.range(0, size).mapToObj(i -> file("f" + (page * 200 + i) + ".txt", page * 200 + i)).toList(), "next-" + page);
        });
        return count;
    }

    private Map<?, ?> data(String tool, String arguments) throws Exception { return (Map<?, ?>) execute(tool, arguments).data(); }
    private AiAgentToolService.ToolExecution execute(String tool, String arguments) throws Exception {
        var args = json.readTree(arguments).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) args).put("storageKey", "S3");
        return service.execute(tool, args);
    }
    private void withContext(CheckedRunnable action) throws Exception {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class); var storage = mockStatic(StorageSourceContext.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(4);
            storage.when(() -> StorageSourceContext.getByStorageId(2)).thenReturn(driver);
            action.run();
            verify(driver, never()).fileList(anyString());
            verify(driver, never()).directoryMetadata(anyString(), anyString());
            verify(driver, never()).hydrateDirectoryPage(anyList(), anyString());
            verify(driver, never()).getDownloadUrl(anyString());
        }
    }
    private static FileItemResult file(String name, long size) {
        var file = new FileItemResult(); file.setName(name); file.setPath("/"); file.setSize(size);
        file.setType(FileTypeEnum.FILE); file.setTime(new Date(size)); return file;
    }
    private interface CheckedRunnable { void run() throws Exception; }
}

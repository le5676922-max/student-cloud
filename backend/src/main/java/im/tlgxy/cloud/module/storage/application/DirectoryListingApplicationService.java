package im.tlgxy.cloud.module.storage.application;

import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.application.port.StorageDriverRegistry;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.DirectoryAccessGuard;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataCache;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataProvider;
import im.tlgxy.cloud.module.storage.service.DirectoryPageService;
import im.tlgxy.cloud.module.storage.service.NativeDirectoryCursorService;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Application boundary for ordinary and provider-native directory browsing.
 *
 * Coordinates existing policy, metadata and cursor services without directly reading HTTP/session state.
 * The compatibility drivers and policy chain still depend on the current session/share context.
 * DTOs and the driver SPI stay unchanged so this migration does not require a frontend release.
 */
@Service
public class DirectoryListingApplicationService {
    private static final String PENDING_URL = "directory-page-pending";

    private final StorageSourceService storageSourceService;
    private final StorageDriverRegistry storageDrivers;
    private final DirectoryMetadataCache directoryMetadataCache;
    private final DirectoryAccessGuard directoryAccessGuard;
    private final NativeDirectoryCursorService nativeDirectoryCursors;
    private final ApiRateLimiter apiRateLimiter;
    private final FileChain fileChain;

    public DirectoryListingApplicationService(StorageSourceService storageSourceService,
                                              StorageDriverRegistry storageDrivers,
                                              DirectoryMetadataCache directoryMetadataCache,
                                              DirectoryAccessGuard directoryAccessGuard,
                                              NativeDirectoryCursorService nativeDirectoryCursors,
                                              ApiRateLimiter apiRateLimiter,
                                              FileChain fileChain) {
        this.storageSourceService = storageSourceService;
        this.storageDrivers = storageDrivers;
        this.directoryMetadataCache = directoryMetadataCache;
        this.directoryAccessGuard = directoryAccessGuard;
        this.nativeDirectoryCursors = nativeDirectoryCursors;
        this.apiRateLimiter = apiRateLimiter;
        this.fileChain = fileChain;
    }

    public FileInfoResult list(FileListRequest request, int actor) throws Exception {
        Integer storageId = storageSourceService.findIdByKey(request.getStorageKey());
        if (storageId == null) {
            throw new InvalidStorageSourceBizException(request.getStorageKey());
        }
        request.handleDefaultValue();
        AbstractBaseFileService<?> driver = storageDrivers.getByStorageId(storageId);
        return request.isNativePagination()
                ? nativeList(request, storageId, driver, actor)
                : ordinaryList(request, storageId, driver, actor);
    }

    private FileInfoResult ordinaryList(FileListRequest request, int storageId,
                                        AbstractBaseFileService<?> driver, int actor) throws Exception {
        DirectoryMetadataProvider provider = request.getPageSize() != null
                && driver instanceof DirectoryMetadataProvider metadata ? metadata : null;
        DirectoryAccessGuard.Grant grant = provider == null ? null
                : directoryAccessGuard.require(actor, storageId, driver.getCurrentUserBasePath());
        List<FileItemResult> files;
        if (provider == null) {
            files = driver.fileList(request.getPath());
        } else {
            String root = grant.root();
            files = directoryMetadataCache.list(driver, actor, root, request.getPath(),
                    request.isRefreshMetadata(), () -> provider.directoryMetadata(request.getPath(), root));
            // The marker exists only in this request. The policy chain may clear it before hydration.
            markReadable(files, grant);
        }

        FileContext context = FileContext.builder().storageId(storageId).fileListRequest(request)
                .fileItemList(files).fileService(driver).build();
        fileChain.execute(context);
        FileInfoResult page = DirectoryPageService.page(context.getFileItemList(), context.getPasswordPattern(),
                request, actor, grant == null ? driver.getCurrentUserBasePath() : grant.root());
        if (provider != null) {
            page.setNativePaginationAvailable(provider.supportsNativePagination());
            DirectoryAccessGuard.Grant latest = directoryAccessGuard.require(actor, storageId, driver.getCurrentUserBasePath());
            if (!Objects.equals(grant, latest)) throw new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED);
            provider.hydrateDirectoryPage(readable(page.getFiles()), grant.root());
        }
        return page;
    }

    private FileInfoResult nativeList(FileListRequest request, int storageId,
                                      AbstractBaseFileService<?> driver, int actor) throws Exception {
        NativeDirectoryCursorService.validate(request);
        if (!(driver instanceof DirectoryMetadataProvider provider) || !provider.supportsNativePagination()) {
            throw new BizException("此存储源暂不支持快速分页，请切换普通浏览");
        }
        var grant = directoryAccessGuard.require(actor, storageId, driver.getCurrentUserBasePath());
        if (!apiRateLimiter.allow("native-directory-list", Integer.toString(actor), 120, 60_000)) {
            throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
        }
        // Password/path checks must precede provider I/O, then current policies run on the returned page.
        FileContext context = FileContext.builder().storageId(storageId).fileListRequest(request)
                .fileItemList(List.of()).fileService(driver).build();
        fileChain.execute(context);
        long generation = directoryMetadataCache.generation();
        var position = nativeDirectoryCursors.resolve(request, driver, actor, grant.root(), generation);
        var remotePage = provider.nativeDirectoryPage(request.getPath(), grant.root(), request.getPageSize(), position.providerToken());
        List<FileItemResult> files = new ArrayList<>(remotePage.files());
        markReadable(files, grant);
        context.setFileItemList(files);
        fileChain.execute(context);
        var latest = directoryAccessGuard.require(actor, storageId, driver.getCurrentUserBasePath());
        if (!Objects.equals(grant, latest) || directoryMetadataCache.generation() != generation) {
            throw new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED);
        }
        provider.hydrateDirectoryPage(readable(context.getFileItemList()), grant.root());
        return nativeDirectoryCursors.complete(position, request, driver, actor, grant.root(),
                directoryMetadataCache.generation(), context.getFileItemList(), context.getPasswordPattern(), remotePage.nextProviderToken());
    }

    private static void markReadable(List<FileItemResult> files, DirectoryAccessGuard.Grant grant) {
        files.stream().filter(file -> file.getType() == FileTypeEnum.FILE && grant.canReadFile())
                .forEach(file -> file.setUrl(PENDING_URL));
    }

    private static List<FileItemResult> readable(List<FileItemResult> files) {
        return files.stream().filter(file -> file.getType() == FileTypeEnum.FILE && PENDING_URL.equals(file.getUrl())).toList();
    }
}

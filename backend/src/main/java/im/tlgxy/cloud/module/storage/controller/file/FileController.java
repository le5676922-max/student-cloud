package im.tlgxy.cloud.module.storage.controller.file;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.annotation.CheckPassword;
import im.tlgxy.cloud.module.storage.annotation.ProCheck;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.convert.StorageSourceConvert;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.request.base.FileItemRequest;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.request.base.SearchStorageRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.DirectoryPageService;
import im.tlgxy.cloud.module.storage.service.DirectoryAccessGuard;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataCache;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataProvider;
import im.tlgxy.cloud.module.storage.service.NativeDirectoryCursorService;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * 文件列表接口控制器。
 *
 * 提供前端页面所需的核心接口，包括：
 * 1. 存储源列表查询（仅返回当前用户有权访问且已启用的存储源）；
 * 2. 文件列表查询（通过责任链处理后返回过滤、排序后的文件列表）；
 * 3. 单个文件信息查询（支持密码校验）。
 *
 * 所有接口路径以 /api/storage 开头，供前端 API 调用。
 *
 * @author zhaojun
 */
@Tag(name = "文件列表模块")
@ApiSort(2)
@Slf4j
@RequestMapping("/api/storage")
@RestController
public class FileController {

	/**
	 * 存储源服务，用于查询存储源信息和根据 key 查找 ID。
	 */
	@Resource
	private StorageSourceService storageSourceService;

    @Resource private DirectoryMetadataCache directoryMetadataCache;
    @Resource private DirectoryAccessGuard directoryAccessGuard;
    @Resource private NativeDirectoryCursorService nativeDirectoryCursors;
    @Resource private ApiRateLimiter apiRateLimiter;

	/**
	 * 文件处理责任链，对文件列表进行权限校验、密码校验、隐藏过滤和排序等处理。
	 */
	@Resource
	private FileChain fileChain;

	/**
	 * 存储源对象转换器，用于 entity 与前端展示对象之间的转换。
	 */
	@Resource
	private StorageSourceConvert storageSourceConvert;

	/**
	 * 获取当前用户可访问的存储源列表。
	 *
	 * 只返回已启用且当前用户有权访问的存储源，按后台配置的排序值升序排列。
	 * 标注了 {@link ProCheck} 注解，表示此功能需要捐赠版授权。
	 *
	 * @return  存储源列表（包含名称、key、类型等基本信息）
	 */
	@ApiOperationSupport(order = 1)
	@Operation(summary = "获取存储源列表", description = "获取所有已启用的存储源, 并且按照后台顺序排序")
	@GetMapping("/list")
	@ProCheck
	public AjaxJson<List<StorageSourceResult>> storageList() {
		// 获取当前用户有权访问的已启用存储源，按排序值升序排列
		List<StorageSource> storageList = storageSourceService.findAllEnableOrderByOrderNum(TlgxyCloudAuthUtil.getCurrentUserId());
		List<StorageSourceResult> storageSourceResultList =
				storageSourceConvert.entityToResultList(storageList);
		return AjaxJson.getSuccessData(storageSourceResultList);
	}

	/**
	 * 获取指定存储源下某个路径的文件和文件夹列表。
	 *
	 * 处理流程：
	 * 1. 根据存储源 key 查找对应的存储源 ID 和文件服务实例；
	 * 2. 调用文件服务的 fileList 方法获取原始文件列表；
	 * 3. 构建 {@link FileContext} 上下文，交由 {@link FileChain} 责任链处理；
	 * 4. 责任链依次执行权限校验、密码校验、隐藏过滤、排序、下载权限校验等命令；
	 * 5. 返回处理后的文件列表和密码路径表达式。
	 *
	 * @param   fileListRequest
	 *          文件列表请求参数，包含 storageKey、path、password、排序参数等
	 *
	 * @return  文件信息结果，包含文件列表和密码路径表达式
	 *
	 * @throws  Exception
	 *          责任链执行过程中可能抛出的异常
	 */
	@ApiOperationSupport(order = 2)
	@Operation(summary = "获取文件列表", description = "获取某个存储源下, 指定路径的文件&文件夹列表")
	@PostMapping("/files")
	public AjaxJson<FileInfoResult> list(@Valid @RequestBody FileListRequest fileListRequest) throws Exception {
		String storageKey = fileListRequest.getStorageKey();
		Integer storageId = storageSourceService.findIdByKey(storageKey);
		if (storageId == null) {
			throw new InvalidStorageSourceBizException(storageKey);
		}

		// 处理请求参数默认值（如未指定排序字段和方向时设置默认值）
		fileListRequest.handleDefaultValue();

		// 获取存储源文件服务实例并查询文件列表
		AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageId(storageId);
        int userId = TlgxyCloudAuthUtil.getCurrentUserId();
        if (fileListRequest.isNativePagination()) return nativeList(fileListRequest, storageId, fileService, userId);
        DirectoryMetadataProvider provider = fileListRequest.getPageSize() != null
                && fileService instanceof DirectoryMetadataProvider metadata ? metadata : null;
        DirectoryAccessGuard.Grant grant = provider == null ? null
                : directoryAccessGuard.require(userId, storageId, fileService.getCurrentUserBasePath());
        List<FileItemResult> fileItemList;
        if (provider == null) {
            fileItemList = fileService.fileList(fileListRequest.getPath());
        } else {
            String root = grant.root();
            fileItemList = directoryMetadataCache.list(fileService, userId, root, fileListRequest.getPath(),
                    fileListRequest.isRefreshMetadata(), () -> provider.directoryMetadata(fileListRequest.getPath(), root));
            // Internal marker preserves the existing permission chain and cursor's URL-availability hash.
            // It is removed/replaced below and is never returned to the client or stored in the cache.
            fileItemList.stream().filter(file -> file.getType() == FileTypeEnum.FILE && grant.canReadFile())
                    .forEach(file -> file.setUrl("directory-page-pending"));
        }

		// 构建文件处理上下文并执行责任链处理（权限校验、密码校验、隐藏过滤、排序等）
		FileContext fileContext = FileContext.builder()
				.storageId(storageId)
				.fileListRequest(fileListRequest)
				.fileItemList(fileItemList)
				.fileService(fileService)
				.build();
		fileChain.execute(fileContext);

		// 返回处理后的文件列表和密码路径表达式（供前端判断子目录的密码保护范围）
        FileInfoResult page = DirectoryPageService.page(fileContext.getFileItemList(), fileContext.getPasswordPattern(),
                fileListRequest, userId, grant == null ? fileService.getCurrentUserBasePath() : grant.root());
        if (provider != null) {
            page.setNativePaginationAvailable(provider.supportsNativePagination());
            DirectoryAccessGuard.Grant latest = directoryAccessGuard.require(userId, storageId, fileService.getCurrentUserBasePath());
            if (!Objects.equals(grant, latest)) throw new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED);
            List<FileItemResult> readable = page.getFiles().stream()
                    .filter(file -> file.getType() == FileTypeEnum.FILE && "directory-page-pending".equals(file.getUrl())).toList();
            provider.hydrateDirectoryPage(readable, grant.root());
        }
        return AjaxJson.getSuccessData(page);
	}

    private AjaxJson<FileInfoResult> nativeList(FileListRequest request, int storageId,
                                               AbstractBaseFileService<?> fileService, int actor) throws Exception {
        NativeDirectoryCursorService.validate(request);
        if (!(fileService instanceof DirectoryMetadataProvider provider) || !provider.supportsNativePagination()) {
            throw new BizException("此存储源暂不支持快速分页，请切换普通浏览");
        }
        var grant = directoryAccessGuard.require(actor, storageId, fileService.getCurrentUserBasePath());
        if (!apiRateLimiter.allow("native-directory-list", Integer.toString(actor), 120, 60_000)) {
            throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
        }
        // Verify path/password before any provider request, then run the same chain on the returned page.
        FileContext context = FileContext.builder().storageId(storageId).fileListRequest(request)
                .fileItemList(List.of()).fileService(fileService).build();
        fileChain.execute(context);
        long generation = directoryMetadataCache.generation();
        var position = nativeDirectoryCursors.resolve(request, fileService, actor, grant.root(), generation);
        var remotePage = provider.nativeDirectoryPage(request.getPath(), grant.root(), request.getPageSize(), position.providerToken());
        List<FileItemResult> files = new java.util.ArrayList<>(remotePage.files());
        files.stream().filter(file -> file.getType() == FileTypeEnum.FILE && grant.canReadFile())
                .forEach(file -> file.setUrl("directory-page-pending"));
        context.setFileItemList(files);
        fileChain.execute(context); // Current hidden/download rules, never a cached grant.
        var latest = directoryAccessGuard.require(actor, storageId, fileService.getCurrentUserBasePath());
        if (!Objects.equals(grant, latest) || directoryMetadataCache.generation() != generation) {
            throw new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED);
        }
        List<FileItemResult> readable = context.getFileItemList().stream()
                .filter(file -> file.getType() == FileTypeEnum.FILE && "directory-page-pending".equals(file.getUrl())).toList();
        provider.hydrateDirectoryPage(readable, grant.root());
        FileInfoResult page = nativeDirectoryCursors.complete(position, request, fileService, actor, grant.root(),
                directoryMetadataCache.generation(), context.getFileItemList(), context.getPasswordPattern(), remotePage.nextProviderToken());
        return AjaxJson.getSuccessData(page);
    }

	/**
	 * 获取单个文件的详细信息。
	 *
	 * 标注了 {@link CheckPassword} 注解，在访问文件时先校验其父目录的密码。
	 * 因为 pathIsDirectory 设置为 false，框架会自动取文件路径的父目录进行密码校验。
	 *
	 * 使用 SpEL 表达式从请求参数中提取存储源 key、文件路径和密码字段值。
	 *
	 * @param   fileItemRequest
	 *          文件请求参数，包含 storageKey、path、password
	 *
	 * @return  单个文件的详细信息（名称、大小、修改时间、下载地址等）
	 */
	@ApiOperationSupport(order = 3)
	@Operation(summary = "获取单个文件信息", description = "获取某个存储源下, 单个文件的信息")
	@PostMapping("/file/item")
	@CheckPassword(storageKeyFieldExpression = "[0].storageKey",
					pathFieldExpression = "[0].path",
					pathIsDirectory = false,
					passwordFieldExpression = "[0].password")
	public AjaxJson<FileItemResult> fileItem(@Valid @RequestBody FileItemRequest fileItemRequest) {
		String storageKey = fileItemRequest.getStorageKey();
		Integer storageId = storageSourceService.findIdByKey(storageKey);
		if (storageId == null) {
			throw new InvalidStorageSourceBizException(storageKey);
		}

		// 处理请求参数默认值
		fileItemRequest.handleDefaultValue();

		// 获取存储源文件服务实例并查询单个文件信息
		AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageId(storageId);

		FileItemResult fileItemResult;
		try {
			fileItemResult = fileService.getFileItem(fileItemRequest.getPath());
		} catch (Exception e) {
			// 捕获异常并包装为业务异常，提供用户友好的错误提示
			throw new BizException(ErrorCode.BIZ_ERROR.getCode(), "获取文件信息失败: " + e.getMessage());
		}

		return AjaxJson.getSuccessData(fileItemResult);
	}

}

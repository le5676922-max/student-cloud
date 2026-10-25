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
import im.tlgxy.cloud.module.storage.application.DirectoryListingApplicationService;
import im.tlgxy.cloud.module.storage.application.port.StorageDriverRegistry;
import im.tlgxy.cloud.module.storage.convert.StorageSourceConvert;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.request.base.FileItemRequest;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

	private final StorageSourceService storageSourceService;
	private final StorageSourceConvert storageSourceConvert;
	private final DirectoryListingApplicationService directoryListing;
	private final StorageDriverRegistry storageDrivers;

	public FileController(StorageSourceService storageSourceService, StorageSourceConvert storageSourceConvert,
						  DirectoryListingApplicationService directoryListing, StorageDriverRegistry storageDrivers) {
		this.storageSourceService = storageSourceService;
		this.storageSourceConvert = storageSourceConvert;
		this.directoryListing = directoryListing;
		this.storageDrivers = storageDrivers;
	}

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
	 * 仅负责当前用户识别和响应包装；目录查询应用服务统一编排普通/快速分页、
	 * 密码及权限校验、缓存和当前页下载地址，保持前端接口与 DTO 不变。
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
		return AjaxJson.getSuccessData(directoryListing.list(fileListRequest, TlgxyCloudAuthUtil.getCurrentUserId()));
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
		AbstractBaseFileService<?> fileService = storageDrivers.getByStorageId(storageId);

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

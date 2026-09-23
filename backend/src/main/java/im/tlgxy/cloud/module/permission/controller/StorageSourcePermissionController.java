package im.tlgxy.cloud.module.permission.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.permission.convert.PermissionConfigConvert;
import im.tlgxy.cloud.module.permission.model.entity.PermissionConfig;
import im.tlgxy.cloud.module.permission.model.result.PermissionConfigResult;
import im.tlgxy.cloud.module.permission.service.PermissionConfigService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存储源权限控制 Controller
 * <p>
 * 提供根据存储源 ID 查询权限配置列表的功能。
 * 将数据库中的权限配置实体转换为前端展示的结果对象，并填充权限名称和提示信息。
 * 所有接口以 /admin 为前缀，仅限管理员访问。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "存储源模块-权限控制")
@ApiSort(6)
@RestController
@RequestMapping("/admin")
public class StorageSourcePermissionController {

	/** 权限配置服务 */
	@Resource
	private PermissionConfigService permissionConfigService;

	/** 权限配置实体转换器 */
	@Resource
	private PermissionConfigConvert permissionConfigConvert;

	@Resource
	private TenantSecurityService tenantSecurityService;

	/**
	 * 获取存储源权限列表
	 * <p>根据存储源 ID 获取该存储源的权限配置列表，并转换为前端可用的结果对象。</p>
	 *
	 * @param storageId 存储源 ID
	 * @return 包含权限配置结果列表的 AjaxJson 对象
	 */
	@ApiOperationSupport(order = 1)
	@Operation(summary = "获取存储源权限列表", description ="根据存储源 ID 获取存储源权限列表")
	@Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
	@GetMapping("/storage/{storageId}/permission")
	public AjaxJson<List<PermissionConfigResult>> getPermissionList(@PathVariable Integer storageId) {
		tenantSecurityService.requireManageStorage(storageId);
		// 查询该存储源的所有权限配置
		List<PermissionConfig> permissionList = permissionConfigService.findByStorageId(storageId);

		// 将实体转换为结果对象
		List<PermissionConfigResult> permissionConfigResults = permissionConfigConvert.toResult(permissionList);
		// 填充权限名称和提示信息（从枚举中获取）
		permissionConfigResults.forEach(permissionConfigResult -> {
			permissionConfigResult.setOperatorName(permissionConfigResult.getOperator().getName());
			permissionConfigResult.setTips(permissionConfigResult.getOperator().getTips());
		});

		return AjaxJson.getSuccessData(permissionConfigResults);
	}

}

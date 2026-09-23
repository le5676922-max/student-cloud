package im.tlgxy.cloud.module.filter.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.filter.model.entity.FilterConfig;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 存储源文件过滤控制器，用于管理指定存储源的文件过滤规则。
 * <p>
 * 文件过滤功能允许管理员为每个存储源配置过滤规则，控制哪些文件或文件夹
 * 在文件列表中显示或隐藏。支持按文件扩展名、文件名、路径等条件进行过滤。
 *
 * @author zhaojun
 */
@Tag(name = "存储源模块-过滤文件")
@ApiSort(6)
@RestController
@RequestMapping("/admin")
public class StorageSourceFilterController {

    /**
     * 过滤配置服务，负责过滤规则的增删改查
     */
    @Resource
    private FilterConfigService filterConfigService;

    @Resource
    private TenantSecurityService tenantSecurityService;

    /**
     * 获取指定存储源的所有文件过滤规则。
     *
     * @param storageId 存储源 ID
     * @return 包含过滤配置列表的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取存储源过滤文件列表", description ="根据存储源 ID 获取存储源设置的过滤文件列表")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @GetMapping("/storage/{storageId}/filters")
    public AjaxJson<List<FilterConfig>> getFilters(@PathVariable Integer storageId) {
        tenantSecurityService.requireManageStorage(storageId);
        return AjaxJson.getSuccessData(filterConfigService.findByStorageId(storageId));
    }

    /**
     * 批量保存指定存储源的文件过滤规则。
     * <p>
     * 会先删除该存储源下的所有旧过滤规则，然后重新保存新的规则列表。
     * 演示站点禁止执行此操作。
     *
     * @param storageId 存储源 ID
     * @param filter    过滤配置列表（前端传递的完整规则列表）
     * @return 操作成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "保存存储源过滤文件列表", description ="保存指定存储源 ID 设置的过滤文件列表")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @PostMapping("/storage/{storageId}/filters")
    @DemoDisable
    public AjaxJson<Void> saveFilters(@PathVariable Integer storageId, @RequestBody List<FilterConfig> filter) {
        tenantSecurityService.requireManageStorage(storageId);
        filterConfigService.batchSave(storageId, filter);
        return AjaxJson.getSuccess();
    }

}

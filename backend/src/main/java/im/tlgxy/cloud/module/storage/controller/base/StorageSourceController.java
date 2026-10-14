package im.tlgxy.cloud.module.storage.controller.base;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.storage.convert.StorageSourceConvert;
import im.tlgxy.cloud.module.storage.model.bo.RefreshTokenCacheBO;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.request.admin.CopyStorageSourceRequest;
import im.tlgxy.cloud.module.storage.model.request.admin.ApplyStorageCorsRequest;
import im.tlgxy.cloud.module.storage.model.request.admin.UpdateStorageSortRequest;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceAdminResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.StorageSourceSafetyService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 存储源管理接口控制器。
 *
 * 提供存储源的 CRUD（增删改查）及辅助管理接口，是管理员后台管理存储源配置的核心控制器。
 *
 * 主要功能包括：
 * 1. 查询所有存储源列表（含 Token 刷新状态信息）；
 * 2. 查询单个存储源详情（含配置参数）；
 * 3. 新增/修改存储源配置；
 * 4. 删除存储源；
 * 5. 启用/禁用存储源（控制前台是否显示）；
 * 6. 调整存储源排序；
 * 7. 校验存储源 key 唯一性；
 * 8. 修改 README 兼容模式；
 * 9. 复制存储源配置。
 *
 * 部分操作标注了 {@link DemoDisable} 注解，表示在演示模式下禁止执行。
 *
 * @author zhaojun
 */
@Tag(name = "存储源模块-基础")
@ApiSort(3)
@RestController
@RequestMapping("/admin")
public class StorageSourceController {

    /**
     * 存储源服务，处理存储源的业务逻辑。
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 存储源对象转换器，用于 entity 与 DTO/VO 之间的转换。
     */
    @Resource
    private StorageSourceConvert storageSourceConvert;

    @Resource
    private TenantSecurityService tenantSecurityService;

    @Resource
    private StorageSourceSafetyService storageSourceSafetyService;

    @PostMapping("/storage/test-connection")
    @Operation(summary = "只读测试未保存的存储配置", description = "不保存配置，不写入文件或远端跨域规则")
    public AjaxJson<StorageSourceSafetyService.PreflightResult> testConnection(@RequestBody SaveStorageSourceRequest request) {
        return AjaxJson.getSuccessData(storageSourceSafetyService.testConnection(request));
    }

    @PostMapping("/storage/{storageId}/cors/apply")
    @DemoDisable
    @Operation(summary = "显式应用已保存的跨域规则", description = "覆盖远端规则，无法通过数据库事务撤销；必须明确确认")
    public AjaxJson<Void> applyCors(@PathVariable Integer storageId, @RequestBody ApplyStorageCorsRequest request) {
        storageSourceSafetyService.applySavedCors(storageId, request != null && request.isConfirmed());
        return AjaxJson.getSuccess();
    }

    /**
     * 获取所有存储源列表。
     *
     * 按排序值由小到大返回所有存储源，同时附加每个存储源的 Token 刷新状态信息
     *（如 accessToken 是否即将过期、上次刷新时间等）。
     *
     * @return  存储源管理列表
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取所有存储源列表", description ="获取所有添加的存储源列表，按照排序值由小到大排序")
    @GetMapping("/storages")
    public AjaxJson<List<StorageSourceAdminResult>> storageList() {
        List<StorageSource> list = storageSourceService.findManageableOrderByOrderNum();

        List<StorageSourceAdminResult> storageSourceAdminResults = storageSourceConvert.entityToAdminResultList(list);

        // 为每个存储源附加 Token 刷新状态信息
        storageSourceAdminResults.forEach(storageSourceAdminResult -> {
            RefreshTokenCacheBO.RefreshTokenInfo refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageSourceAdminResult.getId());
            storageSourceAdminResult.setRefreshTokenInfo(refreshTokenInfo);
        });

        return AjaxJson.getSuccessData(storageSourceAdminResults);
    }

    /**
     * 获取指定存储源的详细信息（含所有配置参数）。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  存储源详细信息和配置参数
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "获取指定存储源参数", description ="获取指定存储源基本信息及其参数")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @GetMapping("/storage/{storageId}")
    public AjaxJson<StorageSourceDTO> storageItem(@PathVariable Integer storageId) {
        StorageSourceDTO storageSourceDTO = storageSourceService.findDTOById(storageId);
        return AjaxJson.getSuccessData(storageSourceDTO);
    }

    /**
     * 保存存储源配置（新增或更新）。
     *
     * 根据请求体中是否包含 ID 来判断是新增还是更新操作。
     * 演示模式下禁止执行此操作。
     *
     * @param   saveStorageSourceRequest
     *          存储源保存请求，包含存储源基本信息和所有配置参数
     *
     * @return  保存后的存储源 ID
     */
    @ApiOperationSupport(order = 3)
    @Operation(summary = "保存存储源参数", description ="保存存储源的所有参数")
    @PostMapping("/storage")
    @DemoDisable
    public AjaxJson<Integer> saveStorageItem(@RequestBody SaveStorageSourceRequest saveStorageSourceRequest) {
        Integer id = storageSourceService.saveStorageSource(saveStorageSourceRequest);
        return AjaxJson.getSuccessData(id);
    }

    /**
     * 删除指定存储源。
     *
     * 会同时删除存储源的基本信息和所有配置参数。
     * 演示模式下禁止执行此操作。
     *
     * @param   storageId
     *          要删除的存储源 ID
     *
     * @return  操作结果
     */
    @ApiOperationSupport(order = 4)
    @Operation(summary = "删除存储源", description ="删除存储源基本设置和拓展设置")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @DeleteMapping("/storage/{storageId}")
    @DemoDisable
    public AjaxJson<Void> deleteStorageItem(@PathVariable Integer storageId) {
        storageSourceService.deleteById(storageId);
        return AjaxJson.getSuccess();
    }

    /**
     * 启用存储源。
     *
     * 启用后存储源会在前台页面显示，用户可以看到和访问该存储源的文件。
     * 演示模式下禁止执行此操作。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  操作结果
     */
    @ApiOperationSupport(order = 5)
    @Operation(summary = "启用存储源", description ="开启存储源后可在前台显示")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @PostMapping("/storage/{storageId}/enable")
    @DemoDisable
    public AjaxJson<Void> enable(@PathVariable Integer storageId) {
        StorageSource storageSource = tenantSecurityService.requireManageStorage(storageId);
        storageSource.setEnable(true);
        storageSourceService.updateById(storageSource);
        return AjaxJson.getSuccess();
    }

    /**
     * 禁用存储源。
     *
     * 禁用后存储源不会在前台页面显示，用户无法访问该存储源。
     * 演示模式下禁止执行此操作。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  操作结果
     */
    @ApiOperationSupport(order = 6)
    @Operation(summary = "停止存储源", description ="停用存储源后不在前台显示")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @PostMapping("/storage/{storageId}/disable")
    @DemoDisable
    public AjaxJson<Void> disable(@PathVariable Integer storageId) {
        StorageSource storageSource = tenantSecurityService.requireManageStorage(storageId);
        storageSource.setEnable(false);
        storageSourceService.updateById(storageSource);
        return AjaxJson.getSuccess();
    }

    /**
     * 更新存储源的排序顺序。
     *
     * 接受一个排序请求列表，批量更新所有存储源的排序值。
     * 排序值决定了存储源在前台页面的显示顺序。
     *
     * @param   updateStorageSortRequestList
     *          存储源排序请求列表，每个元素包含存储源 ID 和新的排序值
     *
     * @return  操作结果
     */
    @ApiOperationSupport(order = 7)
    @Operation(summary = "更新存储源顺序")
    @PostMapping("/storage/sort")
    public AjaxJson<Void> updateStorageSort(@RequestBody List<UpdateStorageSortRequest> updateStorageSortRequestList) {
        storageSourceService.updateStorageSort(updateStorageSortRequestList);
        return AjaxJson.getSuccess();
    }

    /**
     * 校验存储源 key 是否已存在。
     *
     * 存储源 key 是存储源的唯一标识符，用于在前端 URL 中识别不同的存储源。
     * 新增存储源时需要确保 key 不重复。
     *
     * @param   storageKey
     *          要校验的存储源 key
     *
     * @return  true 表示 key 已存在（不可用），false 表示 key 可用
     */
    @ApiOperationSupport(order = 8)
    @Operation(summary = "校验存储源 key 是否重复")
    @Parameter(in = ParameterIn.QUERY, name = "storageKey", description = "存储源 key", required = true, schema = @Schema(type = "string"))
    @GetMapping("/storage/exist/key")
    public AjaxJson<Boolean> existKey(String storageKey) {
        boolean exist = storageSourceService.existByStorageKey(storageKey);
        return AjaxJson.getSuccessData(exist);
    }

    /**
     * 修改 README 兼容模式开关。
     *
     * README 兼容模式是指：当文件夹中包含 README.md 文件时，
     * 是否在文件列表页面的底部渲染显示该文件的内容。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   status
     *          true 表示启用 README 兼容模式，false 表示禁用
     *
     * @return  操作结果
     */
    @ApiOperationSupport(order = 9)
    @Operation(summary = "修改 readme 兼容模式", description ="修改 readme 兼容模式是否启用")
    @Parameters({
        @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer")),
        @Parameter(in = ParameterIn.PATH, name = "status", description = "存储源兼容模式状态", required = true, schema = @Schema(type = "boolean"))
    })
    @PostMapping("/storage/{storageId}/compatibility_readme/{status}")
    public AjaxJson<Void> changeCompatibilityReadme(@PathVariable Integer storageId, @PathVariable Boolean status) {
        StorageSource storageSource = tenantSecurityService.requireManageStorage(storageId);
        storageSource.setCompatibilityReadme(status);
        storageSourceService.updateById(storageSource);
        return AjaxJson.getSuccess();
    }

    /**
     * 复制存储源配置。
     *
     * 基于现有存储源的配置创建一个新的存储源（使用不同的 key 和名称），
     * 适用于需要创建多个相似配置存储源的场景。
     * 演示模式下禁止执行此操作。
     *
     * @param   copyStorageSourceRequest
     *          复制请求，包含源存储源 ID、新存储源的 key 和名称
     *
     * @return  新创建的存储源 ID
     */
    @ApiOperationSupport(order = 10)
    @Operation(summary = "复制存储源", description ="复制存储源配置")
    @PostMapping("/storage/copy")
    @DemoDisable
    public AjaxJson<Integer> copyStorage(@RequestBody @Valid CopyStorageSourceRequest copyStorageSourceRequest) {
        Integer id = storageSourceService.copy(copyStorageSourceRequest);
        return AjaxJson.getSuccessData(id);
    }
}

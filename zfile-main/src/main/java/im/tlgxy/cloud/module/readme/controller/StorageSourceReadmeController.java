package im.tlgxy.cloud.module.readme.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.readme.model.entity.ReadmeConfig;
import im.tlgxy.cloud.module.readme.service.ReadmeConfigService;
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
 * 存储源文档模块维护控制器
 * <p>
 * 提供存储源 README 文档的查询和保存功能。
 * 管理员可以为每个存储源设置 README 文档显示规则（通过表达式匹配文件夹路径，
 * 在指定位置显示 Markdown 格式的说明文档）。
 * 所有接口以 /admin 为前缀，仅限管理员访问。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "存储源模块-README")
@ApiSort(7)
@RestController
@RequestMapping("/admin")
public class StorageSourceReadmeController {

    /** README 文档配置服务 */
    @Resource
    private ReadmeConfigService readmeConfigService;

    @Resource
    private TenantSecurityService tenantSecurityService;

    /**
     * 获取存储源文档文件夹列表
     * <p>根据存储源 ID 获取该存储源下设置的所有 README 文档显示规则。</p>
     *
     * @param storageId 存储源 ID
     * @return 包含 README 配置列表的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取存储源文档文件夹列表", description ="根据存储源 ID 获取存储源设置的文档文件夹列表")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @GetMapping("/storage/{storageId}/readme")
    public AjaxJson<List<ReadmeConfig>> getReadmeList(@PathVariable Integer storageId) {
        tenantSecurityService.requireManageStorage(storageId);
        return AjaxJson.getSuccessData(readmeConfigService.findByStorageId(storageId));
    }


    /**
     * 保存存储源文档文件夹列表
     * <p>批量保存指定存储源的 README 文档显示规则。会先删除原有规则，再插入新规则（在事务中执行）。</p>
     *
     * @param storageId 存储源 ID
     * @param readme    README 配置列表
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "保存存储源文档文件夹列表", description ="保存指定存储源 ID 设置的文档文件夹列表")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @PostMapping("/storage/{storageId}/readme")
    @DemoDisable
    public AjaxJson<Void> saveReadmeList(@PathVariable Integer storageId, @RequestBody List<ReadmeConfig> readme) {
        tenantSecurityService.requireManageStorage(storageId);
        readmeConfigService.batchSave(storageId, readme);
        return AjaxJson.getSuccess();
    }

}

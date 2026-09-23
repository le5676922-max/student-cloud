package im.tlgxy.cloud.module.password.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.password.model.entity.PasswordConfig;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
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
 * 存储源密码维护控制器
 * <p>
 * 提供存储源密码文件夹的查询和保存功能。
 * 管理员可以为每个存储源设置密码保护规则（通过表达式匹配需要密码访问的文件夹路径）。
 * 所有接口以 /admin 为前缀，仅限管理员访问。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "存储源模块-密码文件夹")
@ApiSort(6)
@RestController
@RequestMapping("/admin")
public class StorageSourcePasswordController {

    /** 密码配置服务，封装密码规则的增删改查逻辑 */
    @Resource
    private PasswordConfigService passwordConfigService;

    @Resource
    private TenantSecurityService tenantSecurityService;

    /**
     * 获取存储源密码文件夹列表
     * <p>根据存储源 ID 获取该存储源下设置的所有密码保护规则。</p>
     *
     * @param storageId 存储源 ID
     * @return 包含密码配置列表的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取存储源密码文件夹列表", description ="根据存储源 ID 获取存储源设置的密码文件夹列表")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @GetMapping("/storage/{storageId}/password")
    public AjaxJson<List<PasswordConfig>> getPasswordList(@PathVariable Integer storageId) {
        tenantSecurityService.requireManageStorage(storageId);
        return AjaxJson.getSuccessData(passwordConfigService.findByStorageId(storageId));
    }


    /**
     * 保存存储源密码文件夹列表
     * <p>批量保存指定存储源的密码保护规则。会先删除原有规则，再插入新规则（在事务中执行）。</p>
     *
     * @param storageId 存储源 ID
     * @param password  密码配置列表
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "保存存储源密码文件夹列表", description ="保存指定存储源 ID 设置的密码文件夹列表")
    @Parameter(in = ParameterIn.PATH, name = "storageId", description = "存储源 id", required = true, schema = @Schema(type = "integer"))
    @PostMapping("/storage/{storageId}/password")
    @DemoDisable
    public AjaxJson<Void> savePasswordList(@PathVariable Integer storageId, @RequestBody List<PasswordConfig> password) {
        tenantSecurityService.requireManageStorage(storageId);
        passwordConfigService.batchSave(storageId, password);
        return AjaxJson.getSuccess();
    }

}

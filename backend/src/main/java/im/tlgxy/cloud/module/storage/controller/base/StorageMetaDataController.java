package im.tlgxy.cloud.module.storage.controller.base;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceParamDef;
import im.tlgxy.cloud.core.util.AjaxJson;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存储源元数据接口控制器。
 *
 * 提供存储源相关的元数据查询接口，主要是管理员后台在添加存储源时需要的前端表单信息。
 * 包括：
 * 1. 系统支持的所有存储源类型列表（供用户选择）；
 * 2. 指定存储源类型的配置参数定义（供动态渲染配置表单）。
 *
 * 这些接口属于系统配置层面的 API，路径为 /admin。
 *
 * @author zhaojun
 */
@Tag(name = "存储源模块-元数据")
@ApiSort(4)
@RestController
@RequestMapping("/admin")
public class StorageMetaDataController {

    /**
     * 获取系统支持的所有存储源类型。
     *
     * 返回 {@link StorageTypeEnum} 枚举的所有值，供管理员在添加存储源时选择。
     * 每种存储源类型对应一个云存储或协议（如 S3、OneDrive、本地存储等）。
     *
     * @return  包含所有存储源类型枚举值的 AjaxJson 响应
     */
    @GetMapping("/support-storage")
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取支持的存储源类型", description = "获取系统支持的存储源类型")
    public AjaxJson<StorageTypeEnum[]> supportStorage() {
        return AjaxJson.getSuccessData(StorageTypeEnum.values());
    }

    /**
     * 获取指定存储源类型的所有配置参数定义。
     *
     * 当管理员选择了某个存储源类型后，前端需要根据此接口返回的参数列表动态渲染配置表单。
     * 参数列表包括字段名称、类型（文本框/下拉框/开关等）、是否必填、默认值、描述等信息。
     *
     * 例如：选择 S3 类型时，会返回 AccessKey、SecretKey、Bucket 名称、地域等参数定义。
     *
     * @param   storageType
     *          存储源类型枚举值，由前端通过请求参数传入
     *
     * @return  该存储源类型的所有配置参数定义列表
     */
    @GetMapping("/storage-params")
    @ApiOperationSupport(order = 2)
    @Operation(summary = "获取指定存储源类型的所有参数信息", description = "获取指定存储源类型的参数，如本地存储只需要填路径地址，而对象存储需要填 AccessKey, SecretKey 等信息.")
    public AjaxJson<List<StorageSourceParamDef>> getFormByStorageType(StorageTypeEnum storageType) {
        List<StorageSourceParamDef> storageSourceConfigList = StorageSourceContext.getStorageSourceParamListByType(storageType);
        return AjaxJson.getSuccessData(storageSourceConfigList);
    }

}

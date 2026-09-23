package im.tlgxy.cloud.module.permission.controller;

import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.permission.model.result.PermissionInfoResult;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限信息控制器
 * <p>
 * 提供系统中所有可用权限操作的枚举列表，供前端配置权限时选择。
 * 从 FileOperatorTypeEnum 枚举中获取所有权限定义（排除已废弃的权限），
 * 并封装为权限名称、标识值和提示信息的列表返回。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "权限模块")
@ApiSort(6)
@RestController
@RequestMapping("/admin/permission")
public class PermissionController {

    /**
     * 获取系统中所有可用的权限操作列表
     * <p>
     * 遍历 FileOperatorTypeEnum 枚举的所有值，过滤掉已标记为废弃的权限，
     * 将每个有效权限的名称、标识值和提示信息封装为结果对象返回。
     * </p>
     *
     * @return 包含权限信息列表的 AjaxJson 对象
     */
    @GetMapping("list")
    public AjaxJson<List<PermissionInfoResult>> list() {
        // 获取所有文件操作类型枚举值
        FileOperatorTypeEnum[] values = FileOperatorTypeEnum.values();
        List<PermissionInfoResult> permissionInfoResults = new java.util.ArrayList<>(values.length);
        for (FileOperatorTypeEnum value : values) {
            // 跳过已废弃的权限选项
            if (value.isDeprecated()) {
                continue;
            }
            PermissionInfoResult permissionInfoResult = new PermissionInfoResult();
            permissionInfoResult.setName(value.getName());
            permissionInfoResult.setValue(value.getValue());
            permissionInfoResult.setTips(value.getTips());
            permissionInfoResults.add(permissionInfoResult);
        }
        return AjaxJson.getSuccessData(permissionInfoResults);
    }

}
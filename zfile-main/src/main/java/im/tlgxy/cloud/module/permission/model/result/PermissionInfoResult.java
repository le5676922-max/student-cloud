package im.tlgxy.cloud.module.permission.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 权限信息结果类
 * <p>
 * 用于返回系统中所有可用的权限操作定义。
 * 由 PermissionController 从 FileOperatorTypeEnum 枚举中提取并返回，
 * 供前端在权限配置界面中选择可用的权限类型。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class PermissionInfoResult {

    /** 权限名称（如"下载"、"上传"、"删除"等） */
    @Schema(title="权限名称")
    private String name;

    /** 权限标识值（权限的唯一标识符） */
    @Schema(title="权限标识")
    private String value;

    /** 权限描述/提示信息 */
    @Schema(title="权限描述")
    private String tips;

}
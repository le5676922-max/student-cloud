package im.tlgxy.cloud.module.permission.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.model.dto.FileOperatorTypeDefaultValueDTO;
import lombok.Data;

import java.io.Serializable;

 /**
  * 权限设置实体类
  * <p>
  * 对应数据库表 permission_config，定义存储源的细粒度权限控制。
  * 每条记录指定一种文件操作类型（如下载、上传、删除等）对管理员和匿名用户是否允许。
  * 提供静态工厂方法 getDefaultInstance，使用操作类型的默认值创建权限配置实例。
  * </p>
  *
  * @author zhaojun
  */
@Data
@TableName(value = "`permission_config`")
public class PermissionConfig implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /**
     * 操作类型（如下载、上传、删除、新建文件夹等），使用 FileOperatorTypeEnum 枚举
     */
    @TableField(value = "`operator`")
    private FileOperatorTypeEnum operator;

    /**
     * 是否允许管理员执行此操作
     */
    @TableField(value = "`allow_admin`")
    private Boolean allowAdmin;

    /**
     * 是否允许匿名用户执行此操作
     */
    @TableField(value = "`allow_anonymous`")
    private Boolean allowAnonymous;

    /**
     * 所属存储源 ID
     */
    @TableField(value = "`storage_id`")
    private Integer storageId;

    /**
     * 创建默认权限配置实例
     * <p>根据操作类型枚举中定义的默认值来设置允许管理员和允许匿名用户的初始值。</p>
     *
     * @param storageId 存储源 ID
     * @param operator  操作类型枚举
     * @return 带默认值的权限配置实例
     */
    public static PermissionConfig getDefaultInstance(Integer storageId, FileOperatorTypeEnum operator) {
        PermissionConfig permissionConfig = new PermissionConfig();
        permissionConfig.storageId = storageId;
        permissionConfig.operator = operator;

        // 从操作类型枚举中获取默认权限值
        FileOperatorTypeDefaultValueDTO defaultPermissionValue = operator.getDefaultValue(storageId);
        permissionConfig.allowAdmin = defaultPermissionValue.isAllowAdmin();
        permissionConfig.allowAnonymous = defaultPermissionValue.isAllowAnonymous();

        return permissionConfig;
    }

}
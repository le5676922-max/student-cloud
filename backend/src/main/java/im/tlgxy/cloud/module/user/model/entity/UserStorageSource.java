package im.tlgxy.cloud.module.user.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Set;

/**
 * 用户存储源权限实体类
 * <p>
 * 对应数据库中的 user_storage_source 关联表，存储用户与存储源之间的权限关系。
 * 每个记录表示某个用户对某个存储源拥有哪些操作权限，以及允许访问的根路径。
 * 这是用户权限管理的核心实体，通过该关联表实现细粒度的存储源访问控制。
 * </p>
 * <p>
 * 主要字段说明：
 * - userId：关联用户
 * - storageSourceId：关联存储源
 * - rootPath：该用户在存储源中允许访问的根路径
 * - enable：该权限记录是否启用
 * - permissions：具体的操作权限集合（如读取、写入、删除等）
 * </p>
 *
 * @author zhaojun
 */
@Data
@TableName(value = "user_storage_source")
public class UserStorageSource implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 权限记录主键 ID
     * <p>
     * 使用 INPUT 类型，即由用户在插入前手动指定 ID 值。
     * </p>
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /**
     * 用户 ID
     * <p>
     * 关联 user 表，表示该权限记录属于哪个用户。
     * </p>
     */
    @TableField(value = "user_id")
    private Integer userId;

    /**
     * 存储源 ID
     * <p>
     * 关联存储源表，表示该权限记录适用于哪个存储源。
     * </p>
     */
    @TableField(value = "storage_source_id")
    private Integer storageSourceId;

    /**
     * 允许访问的根路径
     * <p>
     * 限制用户在该存储源中的访问范围，用户只能访问此路径下的文件和目录。
     * 通常为 "/" 表示允许访问整个存储源。
     * </p>
     */
    @TableField(value = "root_path")
    private String rootPath;

    /** Per-member per-source capacity, controlled by the workspace administrator. */
    @TableField(value = "quota_bytes")
    private Long quotaBytes;

    /**
     * 是否启用
     * <p>
     * true 表示该权限记录生效，用户可以按照配置的权限访问对应存储源；
     * false 表示禁用，用户无法访问该存储源。
     * </p>
     */
    @TableField(value = "`enable`")
    private Boolean enable;

    /**
     * 权限集合
     * <p>
     * 用户对该存储源拥有的具体操作权限，如读取文件、写入文件、删除文件等。
     * 使用自定义 TypeHandler（CollectionStrTypeHandler）处理 Java Set 与数据库 JSON 字符串之间的转换。
     * </p>
     */
    @TableField(value = "`permissions`", typeHandler = im.tlgxy.cloud.core.config.mybatis.CollectionStrTypeHandler.class)
    private Set<String> permissions;

}

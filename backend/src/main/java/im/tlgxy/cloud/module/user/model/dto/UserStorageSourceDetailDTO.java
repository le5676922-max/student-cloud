package im.tlgxy.cloud.module.user.model.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * 用户存储源权限详情 DTO（数据传输对象）
 * <p>
 * 用于在前端展示用户的存储源权限配置详情。相比 UserStorageSource 实体，
 * 额外包含了存储源的名称（storageSourceName）和存储策略类型（storageSourceType），
 * 这些字段通过关联查询存储源表获取，方便前端直接展示存储源的完整信息。
 * </p>
 * <p>
 * 实现 Serializable 接口以支持序列化和缓存。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(autoResultMap = true)
public class UserStorageSourceDetailDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 权限记录的唯一标识 ID
     */
    @Schema(title="id")
    private Integer id;

    /**
     * 用户 ID
     */
    @Schema(title="用户 id")
    private Integer userId;

    /**
     * 存储源 ID
     */
    @Schema(title="存储源 ID")
    private Integer storageSourceId;

    /**
     * 存储源名称
     * <p>
     * 相较于实体类额外查询的字段，通过关联存储源表获取，
     * 便于前端直接显示存储源的友好名称。
     * </p>
     */
    @Schema(title="存储源名称")
    private String storageSourceName;

    /**
     * 存储策略类型
     * <p>
     * 相较于实体类额外查询的字段，通过关联存储源表获取，
     * 用于前端区分不同类型的存储源（本地存储、S3、FTP 等）。
     * </p>
     */
    @Schema(title="存储策略类型")
    private StorageTypeEnum storageSourceType;

    /**
     * 允许访问的基础路径
     * <p>
     * 限制用户只能访问存储源中此路径下的文件和目录。
     * </p>
     */
    @Schema(title="允许访问的基础路径")
    private String rootPath;

    /**
     * 是否启用
     * <p>
     * 标记该权限记录是否生效，禁用后用户将无法访问对应的存储源。
     * </p>
     */
    @Schema(title="是否启用")
    private Boolean enable;

    /**
     * 权限列表
     * <p>
     * 用户对该存储源拥有的具体权限集合，使用自定义 TypeHandler 处理
     * Java Set 与数据库 JSON 字符串之间的转换。常见权限值包括读取、写入、删除等。
     * </p>
     */
    @Schema(title="权限列表")
    @TableField(typeHandler = im.tlgxy.cloud.core.config.mybatis.CollectionStrTypeHandler.class)
    private Set<String> permissions;

}
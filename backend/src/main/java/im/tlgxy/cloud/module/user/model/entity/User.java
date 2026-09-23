package im.tlgxy.cloud.module.user.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import im.tlgxy.cloud.core.config.mybatis.CollectionStrTypeHandler;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.Set;

/**
 * 用户实体类
 * <p>
 * 对应数据库中的 user 表，存储系统用户的基本信息。
 * 使用 MyBatis-Plus 注解进行 ORM 映射，JSON 序列化时会忽略密码和盐字段以保证安全。
 * 主要包含用户身份信息（用户名、昵称、密码）、状态信息（是否启用）、
 * 时间戳信息（创建时间、更新时间）以及默认权限配置。
 * </p>
 *
 * @author zhaojun
 */
@Data
@TableName(value = "`user`", autoResultMap = true)
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID，主键，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 用户名，用于登录
     */
    @TableField(value = "`username`")
    private String username;

    /**
     * 用户昵称，用于显示
     */
    @TableField(value = "`nickname`")
    private String nickname;

    /**
     * 登录密码（加密后存储）
     * <p>
     * 使用 @JsonIgnore 注解确保 JSON 序列化时不输出密码字段，防止密码泄露。
     * </p>
     */
    @TableField(value = "`password`")
    @JsonIgnore
    private String password;

    /**
     * 密码加密盐值
     * <p>
     * 用于密码哈希加盐，增加密码安全性。使用 @JsonIgnore 注解确保不会序列化输出。
     * </p>
     */
    @Schema(title="盐")
    @JsonIgnore
    private String salt;

    /**
     * 用户是否启用
     * <p>
     * true 表示启用，可以正常登录；false 表示禁用，无法登录系统。
     * </p>
     */
    @TableField(value = "`enable`")
    private Boolean enable;

    /**
     * 创建时间
     * <p>
     * 使用 MyBatis-Plus 的字段填充策略，插入时自动设置当前时间。
     * </p>
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     * <p>
     * 使用 MyBatis-Plus 的字段填充策略，每次更新时自动设置为当前时间。
     * </p>
     */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private Date updateTime;

    /**
     * 默认权限集合
     * <p>
     * 当系统新增存储源时，会自动为新存储源赋予用户此默认权限集合。
     * 使用自定义 TypeHandler 处理 Java Set 与数据库 JSON 字符串之间的转换。
     * </p>
     */
    @TableField(value = "default_permissions", typeHandler = CollectionStrTypeHandler.class)
    private Set<String> defaultPermissions;

    @TableField(value = "tenant_id")
    private Integer tenantId;

    @TableField(value = "`role`")
    private UserRoleEnum role;

}

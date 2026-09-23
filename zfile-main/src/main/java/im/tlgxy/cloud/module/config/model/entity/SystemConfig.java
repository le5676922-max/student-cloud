package im.tlgxy.cloud.module.config.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 系统配置实体类，对应数据库 system_config 表。
 * <p>
 * 采用 key-value 结构存储系统的各项配置，key 为配置名称（name），value 为配置值（value）。
 * 每个配置项还有一个对应的标题（title）用于后台管理界面展示。
 * <p>
 * 该类还定义了常用配置项的名称常量，方便在代码中引用，避免硬编码字符串。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "系统设置")
@TableName(value = "system_config")
public class SystemConfig implements Serializable {

    /**
     * 直链前缀配置项名称常量
     */
    public static final String DIRECT_LINK_PREFIX_NAME = "directLinkPrefix";

    /**
     * 安全登录入口配置项名称常量
     */
    public static final String SECURE_LOGIN_ENTRY_NAME = "secureLoginEntry";

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /**
     * 配置项名称（key），用于标识配置项
     */
    @TableField(value = "name")
    @Schema(title = "系统设置名称", example = "siteName")
    private String name;

    /**
     * 配置项的值（value），存储实际的配置内容
     */
    @TableField(value = "`value`")
    @Schema(title = "系统设置值", example = "TlgxyCloud 演示站")
    private String value;

    /**
     * 配置项的描述标题，用于后台管理界面展示
     */
    @TableField(value = "title")
    @Schema(title = "系统设置描述", example = "站点名称")
    private String title;

}
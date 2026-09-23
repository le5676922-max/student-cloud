package im.tlgxy.cloud.module.sso.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单点登录（SSO）厂商配置实体类
 * <p>
 * 对应数据库表 sso_config，用于存储 OIDC/OAuth2 单点登录厂商的配置信息。
 * 每个厂商配置包含 OAuth2 协议所需的各项参数（clientId、clientSecret、各端点 URL、scope 等），
 * 以及前端展示相关的信息（名称、图标）和与本地用户绑定的字段表达式。
 * </p>
 *
 * @author OnEvent
 */
@Data
@Schema(title = "单点登录厂商配置")
@TableName(value = "`sso_config`")
public class SsoConfig implements Serializable {

    /** 序列化版本号 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * OIDC/OAuth2 厂商标识名
     * 作为唯一标识，仅可包含数字、字母、短横线和下划线
     */
    @TableField(value = "provider")
    @Schema(title = "OIDC/OAuth2 厂商名", example = "Logto", description = "简称，仅可包含数字、字母，-，_")
    @NotBlank(message = "OIDC/OAuth2 厂商名不能为空")
    private String provider;

    /**
     * SSO 厂商的显示名称
     * 在前端登录页面，用户鼠标悬浮到图标上时显示的名称
     */
    @TableField(value = "`name`")
    @Schema(title = "显示名称", description = "登录页悬浮到图标上的名称")
    @NotBlank(message = "显示名称不能为空")
    private String name;

    /**
     * SSO 厂商的图标
     * 在登录页面显示的图标，支持 URL 链接、SVG 代码或 Base64 编码格式
     */
    @TableField(value = "`icon`")
    @Schema(title = "ICON", description = "登录页显示的图标，支持 URL、SVG、Base64 格式")
    @NotBlank(message = "ICON 不能为空")
    private String icon;

    /**
     * 在 SSO 厂商处注册应用后生成的客户端 ID（Client ID）
     * OAuth2 协议中的必填参数，用于标识应用身份
     */
    @TableField(value = "`client_id`")
    @Schema(title = "在 SSO 厂商处生成的 ID")
    @NotBlank(message = "client_id 不能为空")
    private String clientId;

    /**
     * 在 SSO 厂商处注册应用后生成的客户端密钥（Client Secret）
     * OAuth2 协议中的必填参数，用于验证应用身份，需妥善保管
     */
    @TableField(value = "`client_secret`")
    @Schema(title = "在 SSO 厂商处生成的密钥")
    @NotBlank(message = "client_secret 不能为空")
    private String clientSecret;

    /**
     * SSO 厂商提供的授权端点 URL（Authorization Endpoint）
     * OAuth2 授权码模式下，用户浏览器将被重定向到此地址进行身份认证和授权
     */
    @TableField(value = "`auth_url`")
    @Schema(title = "SSO 厂商提供的授权端点")
    @NotBlank(message = "auth_url 不能为空")
    private String authUrl;

    /**
     * SSO 厂商提供的 Token 端点 URL（Token Endpoint）
     * 后端使用授权码向此地址换取 Access Token 和 ID Token
     */
    @TableField(value = "`token_url`")
    @Schema(title = "SSO 厂商提供的 Token 端点")
    @NotBlank(message = "token_url 不能为空")
    private String tokenUrl;

    /**
     * SSO 厂商提供的用户信息端点 URL（UserInfo Endpoint）
     * 后端使用 Access Token 向此地址请求当前登录用户的身份信息
     */
    @TableField(value = "`user_info_url`")
    @Schema(title = "SSO 厂商提供的用户信息端点")
    @NotBlank(message = "user_info_url 不能为空")
    private String userInfoUrl;

    /**
     * OAuth2 授权范围（Scope）
     * 指定应用需要获取的用户信息权限范围，多个 scope 以空格分隔
     */
    @TableField(value = "`scope`")
    @Schema(title = "SSO 厂商提供的授权范围")
    @NotBlank(message = "scope 不能为空")
    private String scope;

    /**
     * 用户绑定字段表达式
     * 用于从 SSO 返回的用户信息 JSON 中提取特定字段值，
     * 该值将作为本系统用户的唯一标识（用户名），支持 JSON Path 表达式
     */
    @TableField(value = "`binding_field`")
    @Schema(title = "SSO 系统中用户与本系统中用户互相的绑定字段")
    @NotBlank(message = "用户字段表达式不能为空")
    private String bindingField;

    /**
     * 是否启用该 SSO 配置
     * true 表示启用，前端登录页会显示该登录方式；false 表示禁用，不显示且不可用
     */
    @TableField(value = "`enabled`")
    @Schema(title = "是否启用")
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    /**
     * 排序序号
     * 控制登录页上多个 SSO 登录按钮的排列顺序，数字越小越靠前
     */
    @TableField(value = "`order_num`")
    @Schema(title = "排序", description = "数字越小越靠前")
    private Integer orderNum;

}

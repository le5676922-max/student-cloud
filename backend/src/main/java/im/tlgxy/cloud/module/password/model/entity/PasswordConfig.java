package im.tlgxy.cloud.module.password.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 密码设置实体类
 * <p>
 * 对应数据库表 password_config，定义存储源的密码保护规则。
 * 每条规则包含：
 * <ul>
 *   <li>表达式（expression）：用于匹配需要密码访问的文件夹路径（支持 glob 模式）</li>
 *   <li>密码（password）：访问该文件夹所需的密码</li>
 *   <li>描述（description）：便于管理员记忆规则的说明文字</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="密码设置")
@TableName(value = "password_config")
public class PasswordConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.INPUT)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /** 所属存储源 ID */
    @TableField(value = "storage_id")
    @Schema(title = "存储源 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer storageId;

    /** 密码文件夹匹配表达式（支持 glob 模式，如 /*.png） */
    @TableField(value = "expression")
    @Schema(title = "密码文件夹表达式", requiredMode = Schema.RequiredMode.REQUIRED, example = "/*.png")
    private String expression;

    /** 访问密码 */
    @TableField(value = "password")
    @Schema(title = "密码值", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    private String password;

    /** 表达式描述，用于辅助管理员记忆表达式的用途 */
    @TableField(value = "description")
    @Schema(title = "表达式描述", requiredMode = Schema.RequiredMode.REQUIRED, example = "用来辅助记忆表达式")
    private String description;

}
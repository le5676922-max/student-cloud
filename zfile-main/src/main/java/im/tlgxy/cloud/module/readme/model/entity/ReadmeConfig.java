package im.tlgxy.cloud.module.readme.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.module.readme.model.enums.ReadmeDisplayModeEnum;
import im.tlgxy.cloud.module.readme.model.enums.ReadmePathModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * README 文档配置实体类
 * <p>
 * 对应数据库表 readme_config，定义存储源的 README 文档显示规则。
 * 每条规则包含：
 * <ul>
 *   <li>路径表达式（expression）：匹配需要显示 README 的文件夹路径</li>
 *   <li>README 文本内容（readmeText）：支持 Markdown 语法</li>
 *   <li>显示模式（displayMode）：顶部显示(top)、底部显示(bottom)、弹窗显示(dialog)</li>
 *   <li>路径模式（pathMode）：相对路径(relative)或绝对路径(absolute)</li>
 *   <li>描述（description）：辅助管理员记忆规则用途</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="readme 文档配置")
@TableName(value = "`readme_config`")
public class ReadmeConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.INPUT)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /** 所属存储源 ID */
    @TableField(value = "`storage_id`")
    @Schema(title="存储源 ID")
    private Integer storageId;

    /** 表达式描述，用于辅助管理员记忆表达式的用途 */
    @TableField(value = "`description`")
    @Schema(title = "表达式描述", requiredMode = Schema.RequiredMode.REQUIRED, example = "用来辅助记忆表达式")
    private String description;

    /** 路径匹配表达式（支持 glob 模式） */
    @TableField(value = "`expression`")
    @Schema(title="路径表达式")
    private String expression;

    /** README 文本内容，支持 Markdown 语法 */
    @TableField(value = "`readme_text`")
    @Schema(title="readme 文本内容, 支持 md 语法.")
    private String readmeText;

    /** 路径匹配模式：相对路径(relative)或绝对路径(absolute) */
    @TableField(value = "`path_mode`")
    @Schema(title = "路径模式", requiredMode = Schema.RequiredMode.REQUIRED, example = "相等路径或绝对路径")
    private ReadmePathModeEnum pathMode;

    /** 显示模式：顶部(top)、底部(bottom)或弹窗(dialog) */
    @TableField(value = "`display_mode`")
    @Schema(title = "显示模式", requiredMode = Schema.RequiredMode.REQUIRED, example = "readme 显示模式，支持顶部显示: top, 底部显示:bottom, 弹窗显示: dialog")
    private ReadmeDisplayModeEnum displayMode;

}
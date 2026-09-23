package im.tlgxy.cloud.module.filter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import im.tlgxy.cloud.module.filter.model.enums.FilterConfigHiddenModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 存储源文件过滤配置实体类，对应数据库 filter_config 表。
 * <p>
 * 每个过滤规则属于一个存储源，包含：
 * <ul>
 *   <li><b>表达式</b>：用于匹配文件/文件夹的 glob 模式表达式</li>
 *   <li><b>描述</b>：辅助管理员记忆表达式的用途</li>
 *   <li><b>模式</b>：隐藏模式，决定匹配到文件后的处理方式</li>
 * </ul>
 * <p>
 * 隐藏模式由 {@link FilterConfigHiddenModeEnum} 枚举定义，包括：
 * <ul>
 *   <li>仅隐藏（hidden）—— 在文件列表中不显示</li>
 *   <li>隐藏且不可访问（inaccessible）—— 阻止访问目录</li>
 *   <li>隐藏且不可下载（disable_download）—— 阻止下载文件</li>
 * </ul>
 *
 * @author zhaojun
 */
@Data
@Schema(title="存储源过滤配置")
@TableName(value = "filter_config")
public class FilterConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /**
     * 所属存储源的 ID
     */
    @TableField(value = "storage_id")
    @Schema(title = "存储源 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer storageId;

    /**
     * 过滤表达式，支持 glob 模式匹配（如 *.png、**{@literal /}*.tmp 等）
     */
    @TableField(value = "expression")
    @Schema(title = "过滤表达式", requiredMode = Schema.RequiredMode.REQUIRED, example = "/*.png")
    private String expression;

    /**
     * 过滤规则的描述信息，帮助管理员记忆该规则的用途
     */
    @TableField(value = "description")
    @Schema(title = "表达式描述", requiredMode = Schema.RequiredMode.REQUIRED, example = "用来辅助记忆表达式")
    private String description;

    /**
     * 隐藏模式，决定匹配到文件后的处理行为
     */
    @TableField(value = "mode")
    @Schema(title = "模式", requiredMode = Schema.RequiredMode.REQUIRED, example = "隐藏模式，仅隐藏: hidden, 隐藏后不可访问: inaccessible, 隐藏后不可下载: disable_download")
    private FilterConfigHiddenModeEnum mode;

}

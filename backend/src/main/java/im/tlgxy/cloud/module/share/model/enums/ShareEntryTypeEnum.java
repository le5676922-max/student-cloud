package im.tlgxy.cloud.module.share.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分享条目类型枚举
 * <p>
 * 定义分享中每个条目的类型：文件（FILE）或文件夹（FOLDER）。
 * 该枚举用于在创建分享时区分分享内容是单个文件还是文件夹，
 * 也用于前端展示不同的图标和交互行为。
 * </p>
 *
 * <p><b>注解说明：</b></p>
 * <ul>
 *   <li>{@code @EnumValue} — 标记该字段为 MyBatis-Plus 枚举值，存入数据库时使用 value（如 "FILE"、"FOLDER"）</li>
 *   <li>{@code @JsonValue} — 标记该字段为 Jackson 序列化/反序列化时使用的值</li>
 * </ul>
 *
 * @author zhaojun
 * @see im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO
 */
@Schema(description = "分享条目类型")
@Getter
@AllArgsConstructor
public enum ShareEntryTypeEnum {

    /**
     * 文件类型
     * <p>
     * 表示该分享条目是一个文件，例如 PDF 文档、图片、视频等。
     * 数据库中存储的值为 "FILE"。
     * </p>
     */
    @Schema(description = "文件")
    FILE("FILE"),

    /**
     * 文件夹类型
     * <p>
     * 表示该分享条目是一个文件夹（目录），内部可能包含多个子文件和子文件夹。
     * 数据库中存储的值为 "FOLDER"。
     * </p>
     */
    @Schema(description = "文件夹")
    FOLDER("FOLDER");

    /**
     * 枚举值
     * <p>
     * 同时作为 MyBatis-Plus 存入数据库的值（通过 @EnumValue 注解）
     * 和 Jackson 序列化/反序列化的值（通过 @JsonValue 注解）。
     * 例如 "FILE" 或 "FOLDER"。
     * </p>
     */
    @EnumValue
    @JsonValue
    private final String value;

}

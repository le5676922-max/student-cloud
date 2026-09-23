package im.tlgxy.cloud.module.share.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分享类型枚举
 * <p>
 * 定义一次分享操作的类型，用于区分不同的分享场景。
 * 与 {@link im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum} 不同，
 * ShareEntryTypeEnum 描述的是分享中<b>单个条目</b>的类型（文件或文件夹），
 * 而 ShareTypeEnum 描述的是<b>整个分享</b>的类型（单文件、单文件夹或多文件混合）。
 * </p>
 *
 * <p><b>三种类型说明：</b></p>
 * <ul>
 *   <li><b>FILE</b> — 分享单个文件，例如只分享一个 PDF 文档</li>
 *   <li><b>FOLDER</b> — 分享单个文件夹，例如分享整个 "照片" 目录</li>
 *   <li><b>MULTIPLE</b> — 分享多个文件或多个文件夹的混合集合，例如同时选中多个不同目录下的文件进行分享</li>
 * </ul>
 *
 * <p><b>注解说明：</b></p>
 * <ul>
 *   <li>{@code @EnumValue} — 标记 value 字段为 MyBatis-Plus 枚举值，数据库存储 "FILE"、"FOLDER" 或 "MULTIPLE"</li>
 *   <li>{@code @JsonValue} — 标记 value 字段为 Jackson 序列化/反序列化时使用的值</li>
 * </ul>
 *
 * @author zhaojun
 * @see im.tlgxy.cloud.module.share.model.entity.ShareLink
 * @see ShareEntryTypeEnum
 */
@Getter
@AllArgsConstructor
public enum ShareTypeEnum {

    /**
     * 单个文件分享
     * <p>
     * 数据库中存储值为 "FILE"，描述为"单个文件"。
     * 适用于只分享一个文件的场景。
     * </p>
     */
    FILE("FILE", "单个文件"),

    /**
     * 单个文件夹分享
     * <p>
     * 数据库中存储值为 "FOLDER"，描述为"文件夹"。
     * 适用于分享一个完整目录（包含其下所有子文件和子文件夹）的场景。
     * </p>
     */
    FOLDER("FOLDER", "文件夹"),

    /**
     * 多文件/多文件夹混合分享
     * <p>
     * 数据库中存储值为 "MULTIPLE"，描述为"多个文件或文件夹"。
     * 适用于一次性分享多个文件、多个文件夹或文件与文件夹混合的场景。
     * </p>
     */
    MULTIPLE("MULTIPLE", "多个文件或文件夹");

    /**
     * 枚举值
     * <p>
     * 同时作为 MyBatis-Plus 存入数据库的值（通过 @EnumValue 注解）
     * 和 Jackson 序列化/反序列化的值（通过 @JsonValue 注解）。
     * 可能的取值为 "FILE"、"FOLDER" 或 "MULTIPLE"。
     * </p>
     */
    @EnumValue
    @JsonValue
    private final String value;

    /**
     * 类型的中文描述
     * <p>
     * 用于前端展示或日志记录，提供更友好的类型说明。
     * 例如 "单个文件"、"文件夹"、"多个文件或文件夹"。
     * </p>
     */
    private final String description;

}

package im.tlgxy.cloud.module.link.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Referer 防盗链类型枚举
 * <p>
 * 定义了三种 Referer 防盗链模式：
 * <ul>
 *   <li>{@link #OFF}：关闭 Referer 校验，所有请求都不会被拦截</li>
 *   <li>{@link #WHITE_LIST}：白名单模式，只允许 Referer 匹配白名单规则的请求通过</li>
 *   <li>{@link #BLACK_LIST}：黑名单模式，阻止 Referer 匹配黑名单规则的请求</li>
 * </ul>
 * <p>
 * 该枚举同时用于 MyBatis-Plus 的数据库映射（{@link EnumValue}）和
 * Jackson 的 JSON 序列化（{@link JsonValue}），数据库存储和接口返回的值均为
 * 枚举中定义的 value 字符串。
 *
 * @author zhaojun
 */
@Getter
@AllArgsConstructor
public enum RefererTypeEnum {

    /**
     * 不启用 Referer 防盗链，所有请求直接放行
     */
    OFF("off"),

    /**
     * 启用白名单模式，只有 Referer 匹配白名单规则的请求才能访问
     */
    WHITE_LIST("white_list"),

    /**
     * 启用黑名单模式，Referer 匹配黑名单规则的请求将被拒绝
     */
    BLACK_LIST("black_list");

    /**
     * 枚举值，用于数据库存储（MyBatis-Plus）和 JSON 序列化（Jackson）
     */
    @EnumValue
    @JsonValue
    private final String value;

}
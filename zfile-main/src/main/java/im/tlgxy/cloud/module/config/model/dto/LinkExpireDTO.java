package im.tlgxy.cloud.module.config.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 短链/直链过期时间配置 DTO（数据传输对象）。
 * <p>
 * 用于定义短链或直链的有效期选项，包含数值、单位和对应的秒数。
 * 例如：1 天、1 周、1 月、1 年等预设选项，供用户在创建链接时选择。
 * <p>
 * 该 DTO 会以 JSON 数组形式序列化存储在系统配置的 linkExpireTimes 字段中。
 *
 * @author zhaojun
 */
@Data
public class LinkExpireDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 过期时间的数值（如：1 表示 1 天、1 周等）
     */
    private Integer value;

    /**
     * 过期时间的单位（如：day、week、month、year）
     */
    private String unit;

    /**
     * 过期时间对应的总秒数
     */
    private Long seconds;

}

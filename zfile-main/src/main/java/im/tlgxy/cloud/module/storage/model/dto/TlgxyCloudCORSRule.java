package im.tlgxy.cloud.module.storage.model.dto;

import cn.hutool.core.collection.CollUtil;
import im.tlgxy.cloud.core.util.StringUtils;
import lombok.Data;
import software.amazon.awssdk.services.s3.model.CORSRule;

import java.io.Serializable;
import java.util.*;

/**
 * TlgxyCloud 跨域资源共享（CORS）规则数据传输对象（DTO）
 * <p>
 * 封装 S3 兼容存储的跨域访问规则配置。CORS 规则定义了允许哪些来源、
 * 使用哪些 HTTP 方法、携带哪些请求头来访问存储资源。
 * </p>
 * <p>
 * 提供与 AWS SDK 的 {@link CORSRule} 对象之间的双向转换方法：
 * <ul>
 *   <li>{@link #fromCORSRule(List)} - 将 AWS SDK 的 CORS 规则列表转换为 TlgxyCloud 的 CORS 规则列表</li>
 *   <li>{@link #toCORSRule(List)} - 将 TlgxyCloud 的 CORS 规则列表转换为 AWS SDK 的 CORS 规则集合</li>
 * </ul>
 * 在转换过程中会对各列表字段进行排序和去重处理，确保规则定义的一致性和唯一性。
 * </p>
 * <p>
 * 重写了 equals() 和 hashCode() 方法，不将 id 纳入计算。
 * 这意味着两条除 id 外其他属性完全相同的规则被视为相等，
 * 从而在去重（{@link #sortAndDistinct(List)}）时可以合并重复的规则。
 * </p>
 */
@Data
public class TlgxyCloudCORSRule implements Serializable {

    /** CORS 规则的唯一标识 */
    private String id;

    /** 允许的 HTTP 方法列表，如 GET、PUT、POST、DELETE 等 */
    private List<String> allowedMethods;

    /** 允许访问的来源域名列表，如 https://example.com */
    private List<String> allowedOrigins;

    /** 浏览器缓存预检请求结果的最大时间（秒） */
    private Integer maxAgeSeconds;

    /** 允许在响应中暴露给客户端的响应头列表 */
    private List<String> exposedHeaders;

    /** 允许在请求中携带的请求头列表 */
    private List<String> allowedHeaders;

    /**
     * 将 AWS SDK 的 CORS 规则列表转换为 TlgxyCloud 的 CORS 规则列表
     * <p>
     * 遍历所有 AWS SDK CORS 规则，将每个字段逐一转换。
     * 如果 AWS 规则没有 id，则使用 hashCode 作为临时标识。
     * 转换完成后对结果进行排序和去重。
     * </p>
     *
     * @param corsRules  AWS SDK 的 CORS 规则列表
     * @return 排序去重后的 TlgxyCloud CORS 规则列表
     */
    public static List<TlgxyCloudCORSRule> fromCORSRule(List<CORSRule> corsRules) {
        List<TlgxyCloudCORSRule> studentCloudCORSRules = new ArrayList<>();
        for (CORSRule corsRule : corsRules) {
            TlgxyCloudCORSRule studentCloudCORSRule = new TlgxyCloudCORSRule();
            // 如果没有 id，使用 hashCode 作为唯一标识
            studentCloudCORSRule.setId(StringUtils.isEmpty(corsRule.id()) ? String.valueOf(corsRule.hashCode()) : corsRule.id());
            studentCloudCORSRule.setAllowedMethods(new ArrayList<>(corsRule.allowedMethods()));
            studentCloudCORSRule.setAllowedOrigins(new ArrayList<>(corsRule.allowedOrigins()));
            studentCloudCORSRule.setAllowedHeaders(new ArrayList<>(corsRule.allowedHeaders()));
            studentCloudCORSRule.setExposedHeaders(new ArrayList<>(corsRule.exposeHeaders()));
            studentCloudCORSRule.setMaxAgeSeconds(corsRule.maxAgeSeconds());
            studentCloudCORSRules.add(studentCloudCORSRule);
        }
        // 排序和去重，保证规则列表的一致性和唯一性
        return sortAndDistinct(studentCloudCORSRules);
    }

    /**
     * 将 TlgxyCloud 的 CORS 规则列表转换为 AWS SDK 的 CORS 规则集合
     * <p>
     * 先排序去重，然后将每条规则转换为 AWS SDK 的 CORSRule 对象。
     * 返回 HashSet 以保证集合中不包含重复规则。
     * </p>
     *
     * @param studentCloudCORSRules  TlgxyCloud 的 CORS 规则列表
     * @return AWS SDK 的 CORS 规则集合（去重后）
     */
    public static Set<CORSRule> toCORSRule(List<TlgxyCloudCORSRule> studentCloudCORSRules) {
        Set<CORSRule> corsRules = new HashSet<>();
        for (TlgxyCloudCORSRule studentCloudCORSRule : sortAndDistinct(studentCloudCORSRules)) {
            CORSRule.Builder builder = CORSRule.builder();
            builder.id(studentCloudCORSRule.getId());
            builder.allowedMethods(studentCloudCORSRule.getAllowedMethods());
            builder.allowedOrigins(studentCloudCORSRule.getAllowedOrigins());
            builder.allowedHeaders(studentCloudCORSRule.getAllowedHeaders());
            builder.exposeHeaders(studentCloudCORSRule.getExposedHeaders());
            builder.maxAgeSeconds(studentCloudCORSRule.getMaxAgeSeconds());
            corsRules.add(builder.build());
        }
        return corsRules;
    }

    /**
     * 对 CORS 规则列表中的各字段进行排序并整体去重
     * <p>
     * 对每条规则中的方法列表、请求头列表、来源列表、暴露头列表分别进行排序，
     * 确保列表内元素顺序一致。然后使用 Hutool 的 distinct 方法对整体规则列表去重。
     * 去重依赖于 {@link #equals(Object)} 方法（不比较 id），因此配置相同但 id 不同的规则会被合并。
     * </p>
     *
     * @param studentCloudCORSRules  待处理的 TlgxyCloud CORS 规则列表
     * @return 排序和去重后的 TlgxyCloud CORS 规则列表
     */
    public static List<TlgxyCloudCORSRule> sortAndDistinct(List<TlgxyCloudCORSRule> studentCloudCORSRules) {
        // 对每条规则内的列表字段进行排序，确保一致性
        for (TlgxyCloudCORSRule studentCloudCORSRule : studentCloudCORSRules) {
            if (studentCloudCORSRule.getAllowedMethods() != null) {
                Collections.sort(studentCloudCORSRule.getAllowedMethods());
            }
            if (studentCloudCORSRule.getAllowedHeaders() != null) {
                Collections.sort(studentCloudCORSRule.getAllowedHeaders());
            }
            if (studentCloudCORSRule.getAllowedOrigins() != null) {
                Collections.sort(studentCloudCORSRule.getAllowedOrigins());
            }
            if (studentCloudCORSRule.getExposedHeaders() != null) {
                Collections.sort(studentCloudCORSRule.getExposedHeaders());
            }
        }
        // 对整体列表去重（equals 方法不比较 id，所以配置相同的规则被视为重复）
        return CollUtil.distinct(studentCloudCORSRules);
    }



    /**
     * 判断两条 CORS 规则是否相等
     * <p>
     * 只比较业务字段（allowedMethods、allowedOrigins、allowedHeaders、
     * exposedHeaders、maxAgeSeconds），不比较 id。
     * 这样配置相同的规则即使 id 不同也会被视为同一条规则，实现去重。
     * </p>
     *
     * @param o  要比较的对象
     * @return 如果各业务字段都相同则返回 true
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TlgxyCloudCORSRule that = (TlgxyCloudCORSRule) o;
        return Objects.equals(allowedMethods, that.allowedMethods) &&
                Objects.equals(allowedOrigins, that.allowedOrigins) &&
                Objects.equals(maxAgeSeconds, that.maxAgeSeconds) &&
                Objects.equals(exposedHeaders, that.exposedHeaders) &&
                Objects.equals(allowedHeaders, that.allowedHeaders);
    }

    /**
     * 根据业务字段计算哈希码
     * <p>
     * hashCode 与 equals 保持一致，也不包含 id 字段。
     * </p>
     *
     * @return 基于业务字段的哈希码
     */
    @Override
    public int hashCode() {
        return Objects.hash(allowedMethods, allowedOrigins, maxAgeSeconds, exposedHeaders, allowedHeaders);
    }

}
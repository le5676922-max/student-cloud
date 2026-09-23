package im.tlgxy.cloud.core.util.matcher;

import im.tlgxy.cloud.core.util.matcher.impl.AntPathRuleMatcher;
import im.tlgxy.cloud.core.util.matcher.impl.IpRuleMatcher;
import im.tlgxy.cloud.core.util.matcher.impl.RegexRuleMatcher;
import im.tlgxy.cloud.core.util.matcher.impl.SpringSimpleRuleMatcher;

import java.util.HashMap;
import java.util.Map;

/**
 * 规则匹配器工厂类，负责管理和提供 {@link IRuleMatcher} 的实例。
 *
 * <p>该工厂使用<b>注册表模式</b>，在静态初始化块中注册所有可用的规则匹配器实例，
 * 然后通过规则类型标识（{@link IRuleMatcher#getRuleType()}）来查找对应的匹配器。</p>
 *
 * <p>当前注册的规则匹配器包括：</p>
 * <ul>
 *     <li><b>IP 匹配器</b>（{@link IpRuleMatcher}）—— 用于 IP 地址和网段的匹配；</li>
 *     <li><b>正则表达式匹配器</b>（{@link RegexRuleMatcher}）—— 用于正则表达式的匹配；</li>
 *     <li><b>Ant 路径匹配器</b>（{@link AntPathRuleMatcher}）—— 用于 Ant 风格路径模式的匹配；</li>
 *     <li><b>Spring 简单规则匹配器</b>（{@link SpringSimpleRuleMatcher}）—— 用于简单的通配符匹配。</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>
 *     IRuleMatcher matcher = RuleMatcherFactory.getRuleMatcher("regex");
 *     boolean result = matcher.match(".*\\.txt$", "example.txt");
 * </pre>
 *
 * @author zhaojun
 */
public class RuleMatcherFactory {

    /**
     * 规则匹配器注册表，key 为规则类型标识（由 {@link IRuleMatcher#getRuleType()} 返回），
     * value 为对应的规则匹配器实例。
     *
     * <p>使用 HashMap 存储，提供 O(1) 的查找性能。</p>
     */
    private static final Map<String, IRuleMatcher> RULE_MATCHER_MAP = new HashMap<>();

    /**
     * 静态初始化块，负责向注册表中注册所有可用的规则匹配器实例。
     *
     * <p>每个匹配器实例在类加载时创建一次，之后作为单例复用，
     * 避免重复创建对象的开销。</p>
     */
    static {
        // 注册 IP 规则匹配器：支持单个 IP、IP 网段（CIDR）等格式的匹配
        IpRuleMatcher ipRuleMatcher = new IpRuleMatcher();
        RULE_MATCHER_MAP.put(ipRuleMatcher.getRuleType(), ipRuleMatcher);

        // 注册正则表达式规则匹配器：支持标准 Java 正则表达式语法
        RegexRuleMatcher regexRuleMatcher = new RegexRuleMatcher();
        RULE_MATCHER_MAP.put(regexRuleMatcher.getRuleType(), regexRuleMatcher);

        // 注册 Ant 路径规则匹配器：支持 ?（匹配单个字符）和 *（匹配零个或多个字符）等通配符
        AntPathRuleMatcher antPathRuleMatcher = new AntPathRuleMatcher();
        RULE_MATCHER_MAP.put(antPathRuleMatcher.getRuleType(), antPathRuleMatcher);

        // 注册 Spring 简单规则匹配器：基于 Spring 的简单路径匹配策略
        SpringSimpleRuleMatcher springSimpleRuleMatcher = new SpringSimpleRuleMatcher();
        RULE_MATCHER_MAP.put(springSimpleRuleMatcher.getRuleType(), springSimpleRuleMatcher);
    }

    /**
     * 根据规则类型标识获取对应的规则匹配器实例。
     *
     * <p>通过传入的规则类型字符串，在注册表中查找已注册的匹配器实例。
     * 如果传入的规则类型为 null 或空字符串，直接返回 null。</p>
     *
     * @param   ruleType
     *          规则类型标识，如 "ip"、"regex"、"ant"、"simple" 等
     *
     * @return  对应的 {@link IRuleMatcher} 实例；如果 ruleType 为 null/空或未找到匹配的类型则返回 null。
     */
    public static IRuleMatcher getRuleMatcher(String ruleType) {
        // 空值校验：规则类型为 null 或空字符串时直接返回 null
        if (ruleType == null || ruleType.isEmpty()) {
            return null;
        }

        // 从注册表中根据规则类型查找对应的匹配器实例
        return RULE_MATCHER_MAP.get(ruleType);
    }

}
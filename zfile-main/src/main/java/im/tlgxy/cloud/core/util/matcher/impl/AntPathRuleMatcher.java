package im.tlgxy.cloud.core.util.matcher.impl;

import im.tlgxy.cloud.core.constant.RuleTypeConstant;
import im.tlgxy.cloud.core.util.matcher.AbstractRuleMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;

/**
 * Ant 路径匹配器，用于匹配基于 Ant 风格的路径规则。
 * <p>
 *     Ant 路径规则是一种通配符匹配模式，支持以下特殊字符：
 *     <ul>
 *         <li>{@code ?} —— 匹配单个字符</li>
 *         <li>{@code *} —— 匹配零个或多个字符（不跨目录分隔符）</li>
 *         <li>{@code **} —— 匹配零个或多个目录层级</li>
 *     </ul>
 * </p>
 * <p>
 *     该类继承自 {@link AbstractRuleMatcher}，通过 Spring 提供的
 *     {@link AntPathMatcher} 实现具体的匹配逻辑，常用于文件路径、URL 等
 *     具有层级结构的字符串匹配场景。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class AntPathRuleMatcher extends AbstractRuleMatcher {

    /**
     * Spring AntPathMatcher 实例，用于执行实际的 Ant 路径匹配逻辑。
     * 该对象是线程安全的，因此可以作为成员变量复用。
     */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 使用 Ant 路径规则对测试字符串进行匹配。
     * <p>
     *     将规则表达式与测试字符串传入 Spring 的 {@link AntPathMatcher#match(String, String)}
     *     方法进行匹配校验，并在 DEBUG 日志级别下记录匹配结果。
     * </p>
     *
     * @param ruleExpression 规则表达式，符合 Ant 风格的路径模式（如 {@code /api/**}）
     * @param testStr        待校验的测试字符串（如实际的请求路径）
     * @return {@code true} 表示测试字符串与规则表达式匹配；{@code false} 表示不匹配
     */
    @Override
    public boolean match(String ruleExpression, String testStr) {
        boolean match = pathMatcher.match(ruleExpression, testStr);
        if (log.isDebugEnabled()) {
            log.debug("Ant 表达式匹配结果: {}, 规则表达式: {}, 测试值: {}", match, ruleExpression, testStr);
        }
        return match;
    }

    /**
     * 返回当前匹配器对应的规则类型标识。
     *
     * @return 固定返回 {@link RuleTypeConstant#ANT_PATH}，表示 Ant 路径规则类型
     */
    @Override
    public String getRuleType() {
        return RuleTypeConstant.ANT_PATH;
    }

}
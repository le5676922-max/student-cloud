package im.tlgxy.cloud.core.util.matcher.impl;

import im.tlgxy.cloud.core.constant.RuleTypeConstant;
import im.tlgxy.cloud.core.util.matcher.AbstractRuleMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.PatternMatchUtils;

/**
 * Spring Simple 模式匹配器，使用 Spring 框架提供的简单通配符匹配。
 * <p>
 *     该类继承自 {@link AbstractRuleMatcher}，通过 Spring 的
 *     {@link PatternMatchUtils#simpleMatch(String, String)} 实现匹配逻辑。
 *     支持的特殊字符仅有一个：{@code *}（星号），表示匹配任意长度的任意字符序列。
 * </p>
 * <p>
 *     与 {@link AntPathRuleMatcher} 不同，该匹配器不区分目录层级，
 *     {@code *} 可以跨越路径分隔符匹配任意字符，因此更加简单直接。
 *     适用于不需要路径层级感知的简单通配符匹配场景。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class SpringSimpleRuleMatcher extends AbstractRuleMatcher {

    /**
     * 使用 Spring Simple 模式对测试字符串进行完整匹配。
     * <p>
     *     调用 Spring 的 {@link PatternMatchUtils#simpleMatch(String, String)} 方法，
     *     判断测试字符串是否匹配给定的通配符模式。
     *     模式中只有 {@code *} 会被当作通配符处理，其余字符进行精确匹配。
     * </p>
     *
     * @param ruleExpression 规则表达式，支持 {@code *} 通配符（如 {@code *.jpg}）
     * @param testStr        待校验的测试字符串
     * @return {@code true} 表示测试字符串匹配规则；{@code false} 表示不匹配
     */
    @Override
    public boolean match(String ruleExpression, String testStr) {
        boolean match = PatternMatchUtils.simpleMatch(ruleExpression, testStr);
        if (log.isDebugEnabled()) {
            log.debug("Spring Simple 规则匹配结果: {}, 规则表达式: {}, 测试值: {}", match, ruleExpression, testStr);
        }
        return match;
    }

    /**
     * 返回当前匹配器对应的规则类型标识。
     *
     * @return 固定返回 {@link RuleTypeConstant#SPRING_SIMPLE}，表示 Spring Simple 规则类型
     */
    @Override
    public String getRuleType() {
        return RuleTypeConstant.SPRING_SIMPLE;
    }

    /**
     * 使用 Spring Simple 模式判断测试字符串中是否包含匹配的子串。
     * <p>
     *     通过将规则表达式两侧拼接 {@code *} 通配符后调用
     *     {@link #match(String, String)} 来实现"包含"语义。
     *     例如：规则表达式为 {@code "abc"} 时，实际匹配模式为 {@code "*abc*"}，
     *     即测试字符串中任意位置出现 "abc" 都会匹配成功。
     * </p>
     *
     * @param ruleExpression 规则表达式（不含通配符的子串）
     * @param testStr        待校验的测试字符串
     * @return {@code true} 表示测试字符串中包含规则表达式对应的子串；{@code false} 表示不包含
     */
    @Override
    public boolean contains(String ruleExpression, String testStr) {
        // 在规则表达式前后都加上 * 通配符，实现"包含"匹配
        return match("*" + ruleExpression + "*", testStr);
    }

}
package im.tlgxy.cloud.core.util.matcher.impl;

import cn.hutool.core.util.ReUtil;
import im.tlgxy.cloud.core.constant.RuleTypeConstant;
import im.tlgxy.cloud.core.util.matcher.AbstractRuleMatcher;
import lombok.extern.slf4j.Slf4j;

/**
 * 正则表达式匹配器，用于基于正则表达式的规则匹配。
 * <p>
 *     该类继承自 {@link AbstractRuleMatcher}，通过 Hutool 工具类中的
 *     {@link ReUtil} 提供正则匹配和包含判断的能力。
 *     支持两种匹配模式：
 *     <ul>
 *         <li><b>完整匹配（match）</b>：测试字符串必须完全匹配整个正则表达式</li>
 *         <li><b>部分匹配（contains）</b>：测试字符串中是否包含匹配正则表达式的子串</li>
 *     </ul>
 * </p>
 * <p>
 *     正则表达式提供了最灵活的规则定义方式，适用于复杂的字符串模式匹配场景，
 *     如文件名过滤、URL 模式识别等。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class RegexRuleMatcher extends AbstractRuleMatcher {

    /**
     * 使用正则表达式对测试字符串进行完整匹配。
     * <p>
     *     调用 Hutool 的 {@link ReUtil#isMatch(String, CharSequence)} 方法，
     *     判断测试字符串是否完全匹配给定的正则表达式。
     *     注意：完整匹配意味着整个字符串必须从开头到结尾都符合正则模式。
     * </p>
     *
     * @param ruleExpression 规则表达式，即正则表达式（Java 正则语法）
     * @param testStr        待校验的测试字符串
     * @return {@code true} 表示测试字符串完全匹配正则表达式；{@code false} 表示不匹配
     */
    @Override
    public boolean match(String ruleExpression, String testStr) {
        boolean match = ReUtil.isMatch(ruleExpression, testStr);
        if (log.isDebugEnabled()) {
            log.debug("正则匹配结果: {}, 规则表达式: {}, 测试值: {}", match, ruleExpression, testStr);
        }
        return match;
    }

    /**
     * 使用正则表达式判断测试字符串中是否包含匹配的子串。
     * <p>
     *     调用 Hutool 的 {@link ReUtil#contains(String, CharSequence)} 方法，
     *     判断测试字符串中是否存在至少一个子串与正则表达式匹配。
     *     与 {@link #match(String, String)} 不同，该方法不要求整个字符串完全匹配，
     *     只要字符串的任意部分能满足正则表达式即可。
     * </p>
     *
     * @param ruleExpression 规则表达式，即正则表达式（Java 正则语法）
     * @param testStr        待校验的测试字符串
     * @return {@code true} 表示测试字符串中包含匹配正则表达式的子串；{@code false} 表示不包含
     */
    @Override
    public boolean contains(String ruleExpression, String testStr) {
        boolean match = ReUtil.contains(ruleExpression, testStr);
        if (log.isDebugEnabled()) {
            log.debug("正则部分匹配结果: {}, 规则表达式: {}, 测试值: {}", match, ruleExpression, testStr);
        }
        return match;
    }

    /**
     * 返回当前匹配器对应的规则类型标识。
     *
     * @return 固定返回 {@link RuleTypeConstant#REGEX}，表示正则表达式规则类型
     */
    @Override
    public String getRuleType() {
        return RuleTypeConstant.REGEX;
    }

}
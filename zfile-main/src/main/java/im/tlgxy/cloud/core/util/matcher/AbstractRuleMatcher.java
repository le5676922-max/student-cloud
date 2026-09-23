package im.tlgxy.cloud.core.util.matcher;

import java.util.Collection;

/**
 * 抽象规则匹配器，实现 {@link IRuleMatcher} 接口的部分通用方法，用于简化具体规则匹配器的实现。
 *
 * <p>该类提供了以下默认实现：</p>
 * <ul>
 *     <li>{@link #contains(String, String)}：默认委托给 {@link #match(String, String)} 方法，
 *         即"部分匹配"与"完全匹配"行为一致。子类如有不同需求可覆盖此方法。</li>
 *     <li>{@link #matchAny(Collection, String)}：遍历规则表达式列表，只要有一条匹配成功就返回 true。
 *         如果列表为 null 或空，则返回 false。</li>
 *     <li>{@link #matchAnyReturnFirst(Collection, String)}：遍历规则表达式列表，
 *         返回第一个匹配成功的表达式字符串。如果列表为 null 或空，或全部不匹配，则返回 null。</li>
 * </ul>
 *
 * <p>子类只需要实现 {@link #match(String, String)} 和 {@link #getRuleType()} 两个核心方法，
 * 即可获得完整的规则匹配能力。</p>
 *
 * @author zhaojun
 */
public abstract class AbstractRuleMatcher implements IRuleMatcher {

    /**
     * 判断测试字符串是否部分匹配指定的规则表达式。
     *
     * <p>默认实现直接委托给 {@link #match(String, String)} 方法，
     * 即"部分匹配"与"完全匹配"的行为完全一致。
     * 子类可以覆盖此方法以提供不同的匹配逻辑（如模糊匹配、子串匹配等）。</p>
     *
     * @param   ruleExpression
     *          规则表达式
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  如果匹配成功返回 true，否则返回 false。
     */
    @Override
    public boolean contains(String ruleExpression, String testStr) {
        return match(ruleExpression, testStr);
    }

    /**
     * 使用规则表达式列表进行批量匹配，只要有一条规则匹配成功就返回 true。
     *
     * <p>该方法依次遍历规则表达式列表，对每条规则调用 {@link #match(String, String)} 方法。
     * 一旦找到匹配的规则，立即返回 true（短路逻辑），不再继续检查后续规则。</p>
     *
     * <p>如果规则表达式列表为 null 或空，表示没有任何规则约束，直接返回 false。</p>
     *
     * @param   ruleExpressionList
     *          规则表达式列表
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  只要有一条规则匹配成功就返回 true；列表为空或全部不匹配则返回 false。
     */
    @Override
    public boolean matchAny(Collection<String> ruleExpressionList, String testStr) {
        // 如果规则列表为空，表示无规则约束，返回 false
        if (ruleExpressionList == null || ruleExpressionList.isEmpty()) {
            return false;
        }
        // 遍历规则，找到第一个匹配项即短路返回 true
        for (String ruleExpression : ruleExpressionList) {
            if (match(ruleExpression, testStr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 使用规则表达式列表进行批量匹配，返回第一个匹配成功的表达式字符串。
     *
     * <p>该方法依次遍历规则表达式列表，对每条规则调用 {@link #match(String, String)} 方法。
     * 一旦找到匹配的规则，立即返回该规则表达式（短路逻辑），不再继续检查后续规则。</p>
     *
     * <p>如果规则表达式列表为 null 或空，或全部规则都不匹配，则返回 null。</p>
     *
     * @param   ruleExpressionList
     *          规则表达式列表
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  第一个匹配成功的规则表达式字符串；列表为空或全部不匹配则返回 null。
     */
    @Override
    public String matchAnyReturnFirst(Collection<String> ruleExpressionList, String testStr) {
        // 如果规则列表为空，返回 null
        if (ruleExpressionList == null || ruleExpressionList.isEmpty()) {
            return null;
        }
        // 遍历规则，找到第一个匹配项即返回该规则表达式
        for (String ruleExpression : ruleExpressionList) {
            if (match(ruleExpression, testStr)) {
                return ruleExpression;
            }
        }
        return null;
    }

}
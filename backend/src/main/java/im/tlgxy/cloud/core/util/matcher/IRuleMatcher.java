package im.tlgxy.cloud.core.util.matcher;

import java.util.Collection;

/**
 * 规则匹配器接口，定义了规则匹配相关的核心方法。
 *
 * <p>该接口是 TlgxyCloud 中所有规则匹配器（如正则匹配、Ant 路径匹配、IP 匹配等）的统一抽象。
 * 所有具体的规则匹配器实现类都应实现此接口，或继承提供默认实现的
 * {@link AbstractRuleMatcher} 抽象类。</p>
 *
 * <p>接口主要定义了三种匹配模式：</p>
 * <ul>
 *     <li><b>精确匹配 (match)</b>：测试字符串是否完全满足规则表达式；</li>
 *     <li><b>部分匹配 (contains)</b>：测试字符串是否部分满足规则表达式（语义由实现类决定）；</li>
 *     <li><b>批量匹配 (matchAny / matchAnyReturnFirst)</b>：使用多条规则进行匹配，支持短路逻辑。</li>
 * </ul>
 *
 * <p>此外，每个规则匹配器必须声明自己的规则类型（{@link #getRuleType()}），
 * 用于在 {@link RuleMatcherFactory} 中进行注册和查找。</p>
 *
 * @author zhaojun
 */
public interface IRuleMatcher {

    /**
     * 精确匹配：判断测试字符串是否完全满足指定的规则表达式。
     *
     * <p>这是规则匹配的核心方法，具体匹配逻辑由实现类决定。
     * 例如：</p>
     * <ul>
     *     <li>正则匹配器：将规则表达式编译为正则模式，与测试字符串进行完整匹配；</li>
     *     <li>Ant 路径匹配器：使用 Ant 风格的路径模式进行匹配；</li>
     *     <li>IP 匹配器：判断 IP 地址是否在指定的 IP 段或范围内。</li>
     * </ul>
     *
     * @param   ruleExpression
     *          规则表达式，其格式和语义由具体实现类定义
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  如果测试字符串与规则表达式匹配成功返回 true，否则返回 false。
     */
    boolean match(String ruleExpression, String testStr);

    /**
     * 部分匹配：判断测试字符串是否部分满足指定的规则表达式。
     *
     * <p>与 {@link #match(String, String)} 的区别：
     * match 通常要求完全匹配，而 contains 只要求部分匹配。
     * 例如：对于 IP 规则 "192.168.1.0/24"，
     * match 要求完全一致，而 contains 可能只要求 IP 在网段内。</p>
     *
     * @param   ruleExpression
     *          规则表达式
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  如果测试字符串部分匹配规则表达式返回 true，否则返回 false。
     */
    boolean contains(String ruleExpression, String testStr);


    /**
     * 批量匹配（短路版本）：使用多条规则表达式进行匹配，只要有一条匹配成功就返回 true。
     *
     * <p>该方法采用"或"逻辑（短路求值）：</p>
     * <ul>
     *     <li>遍历规则表达式列表，逐一调用 {@link #match(String, String)}；</li>
     *     <li>一旦找到匹配的规则，立即返回 true，不再检查后续规则；</li>
     *     <li>如果所有规则都不匹配，或规则列表为空/null，返回 false。</li>
     * </ul>
     *
     * @param   ruleExpressionList
     *          规则表达式列表
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  只要有一条规则匹配成功就返回 true，否则返回 false。
     */
    boolean matchAny(Collection<String> ruleExpressionList, String testStr);


    /**
     * 批量匹配（返回匹配项）：使用多条规则表达式进行匹配，返回第一个匹配成功的表达式字符串。
     *
     * <p>与 {@link #matchAny(Collection, String)} 类似，但返回的是匹配的那条规则表达式本身而非布尔值。
     * 这在需要知道具体是哪条规则匹配成功时非常有用（如日志记录、策略选择等场景）。</p>
     *
     * <p>短路逻辑：</p>
     * <ul>
     *     <li>遍历规则表达式列表，逐一调用 {@link #match(String, String)}；</li>
     *     <li>一旦找到匹配的规则，立即返回该规则表达式，不再检查后续规则；</li>
     *     <li>如果所有规则都不匹配，或规则列表为空/null，返回 null。</li>
     * </ul>
     *
     * @param   ruleExpressionList
     *          规则表达式列表
     *
     * @param   testStr
     *          待匹配的测试字符串
     *
     * @return  第一个匹配成功的规则表达式字符串；无匹配项则返回 null。
     */
    String matchAnyReturnFirst(Collection<String> ruleExpressionList, String testStr);


    /**
     * 获取当前规则匹配器的规则类型标识。
     *
     * <p>规则类型是一个唯一字符串，用于在 {@link RuleMatcherFactory} 中标识和查找
     * 对应的规则匹配器实例。常见的规则类型包括：</p>
     * <ul>
     *     <li>"ip" —— IP 地址匹配</li>
     *     <li>"regex" —— 正则表达式匹配</li>
     *     <li>"ant" —— Ant 风格路径匹配</li>
     *     <li>"simple" —— Spring 简单规则匹配</li>
     * </ul>
     *
     * @return  规则类型标识字符串，用于工厂类中的注册和查找。
     */
    String getRuleType();


}

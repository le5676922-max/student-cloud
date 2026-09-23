package im.tlgxy.cloud.core.constant;

/**
 * 规则表达式类型常量类。
 * <p>
 * 定义了 TlgxyCloud 系统中支持的几种规则匹配类型，用于文件过滤、路径匹配等场景。
 * 不同的类型使用不同的匹配算法：
 * </p>
 * <ul>
 *   <li><b>ip：</b>IP 地址匹配规则，用于根据客户端 IP 进行访问控制（如白名单、黑名单）。</li>
 *   <li><b>regex：</b>正则表达式规则，灵活性最高，适用于复杂的路径或文件名匹配。</li>
 *   <li><b>antPath：</b>Ant 风格路径匹配规则，支持 *、**、? 等通配符，适用于多级路径匹配。</li>
 *   <li><b>springSimple：</b>Spring Simple 表达式规则，用于基于 Spring EL 的简单条件判断。</li>
 * </ul>
 * <p>
 * 这些常量通常用于数据库中的规则类型字段，通过不同的策略模式来执行对应的匹配逻辑。
 * </p>
 *
 * @author zhaojun
 */
public class RuleTypeConstant {

    /**
     * IP 地址匹配规则
     */
    public static final String IP = "ip";

    /**
     * 正则表达式匹配规则
     */
    public static final String REGEX = "regex";

    /**
     * Ant 风格路径匹配规则（支持 *、**、? 通配符）
     */
    public static final String ANT_PATH = "antPath";

    /**
     * Spring Simple 表达式匹配规则
     */
    public static final String SPRING_SIMPLE = "springSimple";

}

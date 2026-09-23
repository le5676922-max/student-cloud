package im.tlgxy.cloud.core.util.matcher.impl;

import im.tlgxy.cloud.core.constant.RuleTypeConstant;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.matcher.AbstractRuleMatcher;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * IP 匹配器，用于 IP 规则，支持匹配完整 IP 地址或 IP 地址段（CIDR 格式），同时支持 IPv4 和 IPv6。
 * <p>
 *     该类继承自 {@link AbstractRuleMatcher}，通过内部定义的多种规则实现类
 *     （完整 IPv4、完整 IPv6、IPv4 段、IPv6 段）来完成不同类型的 IP 匹配。
 *     规则表达式会被自动识别为对应的 IP 类型，然后创建对应的规则对象进行匹配。
 * </p>
 *
 * <b>规则表达式格式示例：</b>
 * <ul>
 *     <li>IPv4 完整地址：
 *         <ul>
 *             <li>127.0.0.1</li>
 *             <li>192.168.1.100</li>
 *         </ul>
 *     </li>
 *     <li>IPv4 地址段（CIDR）：
 *         <ul>
 *             <li>192.168.0.0/24 —— 匹配 192.168.0.0 ~ 192.168.0.255</li>
 *         </ul>
 *     </li>
 *     <li>IPv6 完整地址：
 *         <ul>
 *             <li>0:0:0:0:0:0:0:1</li>
 *         </ul>
 *     </li>
 *     <li>IPv6 地址段（CIDR）：
 *         <ul>
 *             <li>0:0:0:0:0:0:0:0/64 —— 匹配该前缀范围内的所有 IPv6 地址</li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * @author zhaojun
 */
@Slf4j
public class IpRuleMatcher extends AbstractRuleMatcher {

    /**
     * 使用 IP 规则对测试字符串进行匹配。
     * <p>
     *     首先将测试字符串解析为 {@link InetAddress} 对象，
     *     然后根据规则表达式创建对应的 IP 规则对象（支持完整 IPv4、完整 IPv6、
     *     IPv4 段、IPv6 段四种类型），最后使用规则对象进行匹配判断。
     * </p>
     *
     * @param ruleExpression 规则表达式，可以是完整 IP 地址或 CIDR 格式的 IP 地址段
     * @param testStr        待校验的测试字符串，必须为合法的 IP 地址
     * @return {@code true} 表示测试 IP 与规则表达式匹配；{@code false} 表示不匹配或解析失败
     */
    @Override
    public boolean match(String ruleExpression, String testStr) {
        try {
            // 将测试字符串解析为 InetAddress 对象，支持 IPv4 和 IPv6
            InetAddress inetAddress = InetAddress.getByName(testStr);
            // 根据规则表达式创建对应的 IP 规则对象
            IpRule rule = createRule(ruleExpression);
            // 规则对象存在且匹配成功时返回 true
            boolean match = rule != null && rule.matches(inetAddress);
            if (log.isDebugEnabled()) {
                log.debug("IP 匹配结果: {}, 规则表达式: {}, 测试值: {}, 校验规则: {}", match, ruleExpression, testStr, rule);
            }
            return match;
        } catch (UnknownHostException e) {
            log.error("IP 地址解析失败, ruleExpression: {}, testStr: {}", ruleExpression, testStr);
        }
        return false;
    }

    /**
     * 返回当前匹配器对应的规则类型标识。
     *
     * @return 固定返回 {@link RuleTypeConstant#IP}，表示 IP 规则类型
     */
    @Override
    public String getRuleType() {
        return RuleTypeConstant.IP;
    }

    /**
     * 根据规则表达式创建对应的 IP 规则对象。
     * <p>
     *     会按照 IPv4完整地址 -> IPv6完整地址 -> IPv4地址段 -> IPv6地址段
     *     的顺序依次尝试识别并创建对应的规则对象，匹配到一种类型后立即返回。
     *     如果四种类型都不匹配，则返回 {@code null}。
     * </p>
     *
     * @param ruleExpression 规则表达式字符串
     * @return 匹配的 {@link IpRule} 实现对象，无法识别时返回 {@code null}
     */
    private IpRule createRule(String ruleExpression) {
        if (isValidIpv4(ruleExpression)) {
            return new Ipv4Rule(ruleExpression);
        } else if (isValidIpv6(ruleExpression)) {
            return new Ipv6Rule(ruleExpression);
        } else if (isValidIpv4Range(ruleExpression)) {
            return new Ipv4RangeRule(ruleExpression);
        } else if (isValidIpv6Range(ruleExpression)) {
            return new Ipv6RangeRule(ruleExpression);
        } else {
            return null;
        }
    }

    /**
     * 校验给定字符串是否为合法的完整 IPv4 地址。
     * <p>
     *     通过正则表达式验证格式是否为 {@code xxx.xxx.xxx.xxx}，
     *     每段取值范围为 0~255。
     * </p>
     *
     * @param ipAddress 待校验的字符串
     * @return {@code true} 表示是合法的 IPv4 地址；{@code false} 表示不是
     */
    private boolean isValidIpv4(String ipAddress) {
        // 匹配 0.0.0.0 ~ 255.255.255.255
        String ipv4Pattern = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
                + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
                + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
                + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
        return Pattern.matches(ipv4Pattern, ipAddress);
    }

    /**
     * 校验给定字符串是否为合法的完整 IPv6 地址。
     * <p>
     *     通过 JDK 内置的 {@link InetAddress#getByName(String)} 尝试解析，
     *     并额外判断字符串中是否包含冒号，以区分 IPv6 和 IPv4。
     * </p>
     *
     * @param ipAddress 待校验的字符串
     * @return {@code true} 表示是合法的 IPv6 地址；{@code false} 表示不是
     */
    private boolean isValidIpv6(String ipAddress) {
        try {
            // 使用 JDK 内置方法尝试解析 IPv6 地址
            InetAddress.getByName(ipAddress);
            // 额外判断是否包含冒号，避免 IPv4 被误判为 IPv6
            return ipAddress.contains(":");
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 校验给定字符串是否为合法的 IPv4 地址段（CIDR 格式）。
     * <p>
     *     格式示例：{@code 192.168.0.0/24}
     * </p>
     *
     * @param ipRange 待校验的字符串
     * @return {@code true} 表示是合法的 IPv4 CIDR 格式；{@code false} 表示不是
     */
    private boolean isValidIpv4Range(String ipRange) {
        // 匹配格式：xxx.xxx.xxx.xxx/xx
        String ipv4RangePattern = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}$";
        return Pattern.matches(ipv4RangePattern, ipRange);
    }

    /**
     * 校验给定字符串是否为合法的 IPv6 地址段（CIDR 格式）。
     * <p>
     *     格式示例：{@code 2001:db8::/32}
     * </p>
     *
     * @param ipRange 待校验的字符串
     * @return {@code true} 表示是合法的 IPv6 CIDR 格式；{@code false} 表示不是
     */
    private boolean isValidIpv6Range(String ipRange) {
        // 匹配格式：xxxx:xxxx:...:xxxx/xxx
        String ipv6RangePattern = "^([0-9A-Fa-f]{0,4}:){1,7}([0-9A-Fa-f]{0,4})?/[0-9]{1,3}$";
        return Pattern.matches(ipv6RangePattern, ipRange);
    }

    /**
     * IP 规则接口，定义了 IP 匹配的统一行为。
     * <p>
     *     所有 IP 规则实现类（完整 IPv4、完整 IPv6、IPv4 段、IPv6 段）
     *     都需要实现该接口，提供匹配判断和表达式获取的能力。
     * </p>
     */
    private interface IpRule {

        /**
         * 判断给定的 IP 地址是否与当前规则匹配。
         *
         * @param ipAddress 待匹配的 IP 地址对象
         * @return {@code true} 表示匹配；{@code false} 表示不匹配
         */
        boolean matches(InetAddress ipAddress);

        /**
         * 获取当前规则的表达式字符串。
         *
         * @return 规则表达式字符串
         */
        String getExpression();

    }

    /**
     * 完整 IPv4 地址规则实现类。
     * <p>
     *     用于精确匹配单个 IPv4 地址，只有测试 IP 与规则表达式
     *     完全相同时才返回匹配成功。
     * </p>
     */
    private static class Ipv4Rule implements IpRule {

        /**
         * IPv4 地址表达式，格式如 {@code 192.168.1.1}
         */
        private final String expression;

        /**
         * 构造一个完整 IPv4 规则对象。
         *
         * @param expression IPv4 地址字符串
         */
        Ipv4Rule(String expression) {
            this.expression = expression;
        }

        /**
         * 判断给定的 IP 地址是否与当前规则匹配。
         * <p>
         *     只有测试 IP 为 IPv4 地址且与表达式完全相同时才返回 {@code true}。
         * </p>
         *
         * @param ipAddress 待匹配的 IP 地址对象
         * @return {@code true} 表示地址完全匹配；{@code false} 表示不匹配或地址类型不符
         */
        @Override
        public boolean matches(InetAddress ipAddress) {
            // 如果传入的是 IPv6 地址，直接返回不匹配
            if (ipAddress instanceof Inet6Address) {
                return false;
            }
            // 比较地址字符串是否完全一致
            return ipAddress.getHostAddress().equals(expression);
        }

        @Override
        public String getExpression() {
            return expression;
        }
    }

    /**
     * 完整 IPv6 地址规则实现类。
     * <p>
     *     用于精确匹配单个 IPv6 地址，只有测试 IP 为 IPv6 且
     *     与规则表达式完全相同时才返回匹配成功。
     * </p>
     */
    private static class Ipv6Rule implements IpRule {

        /**
         * IPv6 地址表达式，格式如 {@code 0:0:0:0:0:0:0:1}
         */
        private final String expression;

        /**
         * 构造一个完整 IPv6 规则对象。
         *
         * @param expression IPv6 地址字符串
         */
        Ipv6Rule(String expression) {
            this.expression = expression;
        }

        /**
         * 判断给定的 IP 地址是否与当前规则匹配。
         * <p>
         *     只有测试 IP 为 IPv6 地址且与表达式完全相同时才返回 {@code true}。
         * </p>
         *
         * @param ipAddress 待匹配的 IP 地址对象
         * @return {@code true} 表示地址完全匹配；{@code false} 表示不匹配或地址类型不符
         */
        @Override
        public boolean matches(InetAddress ipAddress) {
            // 仅当传入的地址是 IPv6 地址时才进行比较
            if (ipAddress instanceof Inet6Address) {
                return ipAddress.getHostAddress().equals(expression);
            }
            return false;
        }

        @Override
        public String getExpression() {
            return expression;
        }
    }

    /**
     * IPv4 地址段（CIDR）规则实现类。
     * <p>
     *     通过前缀长度（prefixLength）来定义 IPv4 地址范围，
     *     匹配时会比较 IP 地址的前 prefixLength 位是否与规则的网络地址一致。
     * </p>
     * <p>
     *     例如：规则 {@code 192.168.0.0/24} 表示网络前缀长度为 24 位，
     *     即前 24 位（前 3 个字节）必须完全一致，后 8 位可以是任意值。
     * </p>
     */
    private static class Ipv4RangeRule implements IpRule {

        /**
         * IPv4 网络地址部分（去除了 CIDR 后缀的 IP 地址）
         */
        private final String expression;

        /**
         * 网络前缀长度，取值范围为 0~32
         */
        private final int prefixLength;

        /**
         * 构造一个 IPv4 地址段规则对象。
         * <p>
         *     从 CIDR 格式的表达式（如 {@code 192.168.0.0/24}）中
         *     解析出网络地址和前缀长度。
         * </p>
         *
         * @param expression CIDR 格式的 IPv4 地址段表达式
         */
        Ipv4RangeRule(String expression) {
            // 提取斜杠前的 IP 地址部分
            this.expression = expression.substring(0, expression.indexOf('/'));
            // 提取斜杠后的前缀长度
            this.prefixLength = Integer.parseInt(expression.substring(expression.indexOf('/') + 1));
        }

        /**
         * 判断给定的 IP 地址是否在当前的 IPv4 地址段内。
         * <p>
         *     通过逐字节比较前 prefixLength 位是否一致来进行匹配。
         * </p>
         *
         * @param ipAddress 待匹配的 IP 地址对象
         * @return {@code true} 表示 IP 地址在规则定义的范围内；{@code false} 表示不在范围内或地址类型不符
         */
        @Override
        public boolean matches(InetAddress ipAddress) {
            // IPv6 地址不参与 IPv4 地址段匹配
            if (ipAddress instanceof Inet6Address) {
                return false;
            }

            // 将规则中的网络地址字符串按点号分割，转换为字节数组
            String[] rangeParts = expression.split("\\.");
            byte[] rangeAddrBytes = new byte[4];
            for (int i = 0; i < rangeParts.length; i++) {
                rangeAddrBytes[i] = (byte) Integer.parseInt(rangeParts[i]);
            }

            // 获取测试 IP 的原始字节数组
            byte[] ipValue = ipAddress.getAddress();
            // IPv4 地址固定为 4 字节
            if (ipValue.length != 4) {
                return false;
            }

            // 逐字节比较完整的前缀部分（每 8 位一组）
            for (int i = 0; i < prefixLength / 8; i++) {
                if (rangeAddrBytes[i] != ipValue[i]) {
                    return false;
                }
            }

            // 处理剩余不足 8 位的部分（如果有的话）
            int remainingBits = prefixLength % 8;
            if (remainingBits != 0) {
                int rangeByte = rangeAddrBytes[prefixLength / 8];
                int ipByte = ipValue[prefixLength / 8];

                // 构造掩码，只比较前 remainingBits 位
                int shift = 8 - remainingBits;
                int mask = 0xFF >> shift;

                return (rangeByte >> shift & mask) == (ipByte >> shift & mask);
            }

            return true;
        }

        @Override
        public String getExpression() {
            return expression + StringUtils.SLASH + prefixLength;
        }
    }

    /**
     * IPv6 地址段（CIDR）规则实现类。
     * <p>
     *     通过前缀长度（prefixLength）来定义 IPv6 地址范围，
     *     匹配时会比较 IP 地址的前 prefixLength 位是否与规则的网络地址一致。
     * </p>
     * <p>
     *     例如：规则 {@code 2001:db8::/32} 表示网络前缀长度为 32 位，
     *     即前 32 位（前 4 个字节）必须完全一致。
     * </p>
     */
    private static class Ipv6RangeRule implements IpRule {

        /**
         * IPv6 网络地址部分（去除了 CIDR 后缀的 IP 地址）
         */
        private final String expression;

        /**
         * 网络前缀长度，取值范围为 0~128
         */
        private final int prefixLength;

        /**
         * 构造一个 IPv6 地址段规则对象。
         * <p>
         *     从 CIDR 格式的表达式（如 {@code 2001:db8::/32}）中
         *     解析出网络地址和前缀长度。
         * </p>
         *
         * @param expression CIDR 格式的 IPv6 地址段表达式
         */
        Ipv6RangeRule(String expression) {
            // 提取斜杠前的 IPv6 地址部分
            this.expression = expression.substring(0, expression.indexOf('/'));
            // 提取斜杠后的前缀长度
            this.prefixLength = Integer.parseInt(expression.substring(expression.indexOf('/') + 1));
        }

        /**
         * 判断给定的 IP 地址是否在当前的 IPv6 地址段内。
         * <p>
         *     通过逐字节比较前 prefixLength 位是否一致来进行匹配。
         *     使用 {@link Inet6AddressConverter} 将 IPv6 地址字符串转换为字节数组。
         * </p>
         *
         * @param ipAddress 待匹配的 IP 地址对象
         * @return {@code true} 表示 IP 地址在规则定义的范围内；{@code false} 表示不在范围内或地址类型不符
         */
        @Override
        public boolean matches(InetAddress ipAddress) {
            // 仅当传入的地址是 IPv6 地址时才进行匹配
            if (ipAddress instanceof Inet6Address) {
                byte[] ipValue = ipAddress.getAddress();
                // 将规则中的 IPv6 地址字符串转换为字节数组
                byte[] rangeValue = Inet6AddressConverter.convert(expression);

                // IPv6 地址固定为 16 字节
                if (ipValue.length != 16) {
                    return false;
                }

                // 逐字节比较完整的前缀部分（每 8 位一组）
                for (int i = 0; i < prefixLength / 8; i++) {
                    if (rangeValue[i] != ipValue[i]) {
                        return false;
                    }
                }

                // 处理剩余不足 8 位的部分（如果有的话）
                int remainingBits = prefixLength % 8;
                if (remainingBits != 0) {
                    int rangeByte = rangeValue[prefixLength / 8];
                    int ipByte = ipValue[prefixLength / 8];

                    // 构造掩码，只比较前 remainingBits 位
                    int shift = 8 - remainingBits;
                    int mask = 0xFF >> shift;

                    return (rangeByte >> shift & mask) == (ipByte >> shift & mask);
                }

                return true;
            }
            return false;
        }

        @Override
        public String getExpression() {
            return expression + StringUtils.SLASH + prefixLength;
        }
    }

    /**
     * IPv6 地址字节数组转换工具类。
     * <p>
     *     负责将 IPv6 地址的字符串表示形式（如 {@code 2001:0db8:0000:0000:0000:0000:0000:0001}）
     *     转换为 16 字节的字节数组，以便进行前缀匹配时的逐字节比较。
     * </p>
     */
    private static class Inet6AddressConverter {

        /**
         * 将 IPv6 地址字符串转换为 16 字节的字节数组。
         * <p>
         *     按冒号分割地址字符串，每个块（block）为 4 位十六进制数（2 字节），
         *     依次填充到字节数组中。
         * </p>
         *
         * @param ipv6Address IPv6 地址字符串
         * @return 长度为 16 的字节数组，表示 IPv6 地址的二进制形式
         */
        public static byte[] convert(String ipv6Address) {
            byte[] ipAddress = new byte[16];
            // 按冒号分割 IPv6 地址的各个块
            String[] blocks = ipv6Address.split(":");

            for (int i = 0; i < blocks.length; i++) {
                String block = blocks[i];
                if (!block.isEmpty()) {
                    // 每个块为 4 位十六进制数，高 2 位在前，低 2 位在后
                    ipAddress[i * 2] = (byte) Integer.parseInt(block.substring(0, 2), 16);
                    ipAddress[i * 2 + 1] = (byte) Integer.parseInt(block.substring(2, 4), 16);
                }
            }

            return ipAddress;
        }
    }
}
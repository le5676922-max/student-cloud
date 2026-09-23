package im.tlgxy.cloud.core.config.totp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TOTP（基于时间的一次性密码）配置属性类。
 * <p>
 * 将 Spring Boot 配置文件（如 application.yml）中以 "totp" 为前缀的配置项
 * 自动映射到该类的属性上，用于控制 TOTP 验证码的各项参数。
 * </p>
 * <p>
 * 配置示例：<br>
 * <pre>{@code
 * totp:
 *   secret:
 *     length: 32   # 密钥长度（字节）
 *   code:
 *     length: 6    # 验证码位数
 *   time:
 *     period: 30       # 时间步长（秒）
 *     discrepancy: 1   # 容错步长（前后各 N 个窗口）
 * }</pre>
 * </p>
 * <p>
 * 包含三个内部配置类：
 * </p>
 * <ul>
 *   <li><b>Secret：</b>密钥相关配置（长度）。</li>
 *   <li><b>Code：</b>验证码相关配置（位数）。</li>
 *   <li><b>Time：</b>时间相关配置（步长、容错步长）。</li>
 * </ul>
 */
// 绑定配置文件中以 "totp" 为前缀的属性
@ConfigurationProperties(
    prefix = "totp"
)
public class TotpProperties {

    /**
     * 默认密钥长度：32 字节
     */
    private static final int DEFAULT_SECRET_LENGTH = 32;

    /**
     * 默认验证码位数：6 位
     */
    private static final int DEFAULT_CODE_LENGTH = 6;

    /**
     * 默认时间步长：30 秒
     */
    private static final int DEFAULT_TIME_PERIOD = 30;

    /**
     * 默认容错步长：1（即允许前后各一个时间窗口内的验证码）
     */
    private static final int DEFAULT_TIME_DISCREPANCY = 1;

    /**
     * 密钥相关配置
     */
    private final Secret secret = new Secret();

    /**
     * 验证码相关配置
     */
    private final Code code = new Code();

    /**
     * 时间相关配置
     */
    private final Time time = new Time();

    public TotpProperties() {
    }

    /**
     * 获取密钥配置。
     *
     * @return Secret 配置对象
     */
    public Secret getSecret() {
        return this.secret;
    }

    /**
     * 获取验证码配置。
     *
     * @return Code 配置对象
     */
    public Code getCode() {
        return this.code;
    }

    /**
     * 获取时间配置。
     *
     * @return Time 配置对象
     */
    public Time getTime() {
        return this.time;
    }

    /**
     * 时间相关配置内部类。
     * <p>
     * period：每个 TOTP 验证码的有效时间窗口（秒），默认 30 秒。<br>
     * discrepancy：允许的时间偏差步长数，默认为 1。
     * 例如设置为 1 时，系统会认为当前时间窗口前后各一个窗口内的验证码也是有效的，
     * 用于处理客户端与服务器之间的时间偏差。
     * </p>
     */
    public static class Time {
        /**
         * 时间步长，单位：秒，默认 30
         */
        private int period = 30;

        /**
         * 容错步长，默认为 1
         */
        private int discrepancy = 1;

        public Time() {
        }

        public int getPeriod() {
            return this.period;
        }

        public void setPeriod(int period) {
            this.period = period;
        }

        public int getDiscrepancy() {
            return this.discrepancy;
        }

        public void setDiscrepancy(int discrepancy) {
            this.discrepancy = discrepancy;
        }
    }

    /**
     * 验证码相关配置内部类。
     * <p>
     * length：TOTP 验证码的位数，默认为 6 位。
     * </p>
     */
    public static class Code {
        /**
         * 验证码长度，默认 6 位
         */
        private int length = 6;

        public Code() {
        }

        public int getLength() {
            return this.length;
        }

        public void setLength(int length) {
            this.length = length;
        }
    }

    /**
     * 密钥相关配置内部类。
     * <p>
     * length：TOTP 密钥的长度（字节数），默认为 32 字节。
     * 密钥越长安全性越高，但生成的二维码也越复杂。
     * </p>
     */
    public static class Secret {
        /**
         * 密钥长度（字节），默认 32
         */
        private int length = 32;

        public Secret() {
        }

        public int getLength() {
            return this.length;
        }

        public void setLength(int length) {
            this.length = length;
        }
    }
}

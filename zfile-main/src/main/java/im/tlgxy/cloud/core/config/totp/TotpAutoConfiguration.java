package im.tlgxy.cloud.core.config.totp;

import dev.samstevens.totp.TotpInfo;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.QrDataFactory;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TOTP（基于时间的一次性密码）自动配置类。
 * <p>
 * 当 classpath 中存在 {@code dev.samstevens.totp.TotpInfo} 类（即引入了 TOTP 相关依赖）时，
 * 自动装配 TOTP 相关的 Bean，包括：
 * </p>
 * <ul>
 *   <li><b>SecretGenerator：</b>密钥生成器，用于为每个用户生成唯一的 TOTP 密钥。</li>
 *   <li><b>TimeProvider：</b>时间提供者，使用系统时间。</li>
 *   <li><b>HashingAlgorithm：</b>哈希算法，默认使用 SHA1。</li>
 *   <li><b>CodeGenerator：</b>验证码生成器，根据密钥和时间生成 6 位数字验证码。</li>
 *   <li><b>CodeVerifier：</b>验证码校验器，用于校验用户输入的验证码是否正确。</li>
 *   <li><b>QrDataFactory：</b>二维码数据工厂，用于生成绑定 TOTP 所需的二维码数据。</li>
 * </ul>
 * <p>
 * 配置参数从 {@link TotpProperties} 中读取（配置文件前缀为 {@code totp}），
 * 包括密钥长度、验证码长度、时间步长和容错步长等。
 * </p>
 */
@Configuration
// 仅当 classpath 中存在 TotpInfo 类时才启用此配置
@ConditionalOnClass({TotpInfo.class})
// 启用 TotpProperties 配置属性绑定
@EnableConfigurationProperties({TotpProperties.class})
public class TotpAutoConfiguration {

    /**
     * TOTP 配置属性
     */
    private final TotpProperties props;

    /**
     * 构造方法，注入 TOTP 配置属性。
     *
     * @param props TOTP 配置属性对象
     */
    @Autowired
    public TotpAutoConfiguration(TotpProperties props) {
        this.props = props;
    }

    /**
     * 创建密钥生成器 Bean。
     * <p>
     * 使用配置的密钥长度（默认为 32 字节）生成随机的 Base32 编码密钥。
     * </p>
     *
     * @return SecretGenerator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretGenerator secretGenerator() {
        int length = this.props.getSecret().getLength();
        return new DefaultSecretGenerator(length);
    }

    /**
     * 创建时间提供者 Bean。
     * <p>
     * 使用系统时间作为 TOTP 的时间基准。
     * </p>
     *
     * @return TimeProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public TimeProvider timeProvider() {
        return new SystemTimeProvider();
    }

    /**
     * 创建哈希算法 Bean。
     * <p>
     * 目前固定使用 SHA1 算法。
     * </p>
     *
     * @return HashingAlgorithm 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HashingAlgorithm hashingAlgorithm() {
        return HashingAlgorithm.SHA1;
    }

    /**
     * 创建二维码数据工厂 Bean。
     * <p>
     * 用于生成 Google Authenticator 等 TOTP 客户端所需的二维码数据（otpauth:// URI 格式）。
     * </p>
     *
     * @param hashingAlgorithm 哈希算法（会被 Spring 自动注入）
     * @return QrDataFactory 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public QrDataFactory qrDataFactory(HashingAlgorithm hashingAlgorithm) {
        return new QrDataFactory(hashingAlgorithm, this.getCodeLength(), this.getTimePeriod());
    }

    /**
     * 创建验证码生成器 Bean。
     * <p>
     * 根据密钥和当前时间步长计算 TOTP 验证码（通常为 6 位数字）。
     * </p>
     *
     * @param algorithm 哈希算法
     * @return CodeGenerator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CodeGenerator codeGenerator(HashingAlgorithm algorithm) {
        return new DefaultCodeGenerator(algorithm, this.getCodeLength());
    }

    /**
     * 创建验证码校验器 Bean。
     * <p>
     * 用于校验用户输入的验证码是否与当前时间窗口内的期望值匹配。
     * 支持一定的容错步长（通过 {@code allowedTimePeriodDiscrepancy} 配置），
     * 以应对客户端和服务器之间可能存在的时间偏差。
     * </p>
     *
     * @param codeGenerator 验证码生成器
     * @param timeProvider  时间提供者
     * @return CodeVerifier 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CodeVerifier codeVerifier(CodeGenerator codeGenerator, TimeProvider timeProvider) {
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        // 设置时间步长（每个验证码的有效时间窗口，默认 30 秒）
        verifier.setTimePeriod(this.getTimePeriod());
        // 设置允许的时间偏差步长数（默认为 1，表示前后各一个时间窗口内的验证码也认为有效）
        verifier.setAllowedTimePeriodDiscrepancy(this.props.getTime().getDiscrepancy());
        return verifier;
    }

    /**
     * 获取验证码长度配置。
     *
     * @return 验证码的位数（默认 6）
     */
    private int getCodeLength() {
        return this.props.getCode().getLength();
    }

    /**
     * 获取时间步长配置。
     *
     * @return 每个验证码的有效时间（秒，默认 30）
     */
    private int getTimePeriod() {
        return this.props.getTime().getPeriod();
    }
}

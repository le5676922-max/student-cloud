package im.tlgxy.cloud.core.config.spring;

import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * TlgxyCloud Web MVC 配置类。
 * <p>
 * 主要完成两个方面的 Web 层配置：
 * </p>
 * <ol>
 *   <li>
 *     <b>Tomcat 特殊字符支持：</b>修改内嵌 Tomcat 的配置，允许 URL 路径和查询参数中
 *     包含特殊字符 {@code <>[\]^`{|}%[]}，这对于网盘文件系统而言非常重要，
 *     因为文件名可能包含这些特殊字符。
 *   </li>
 *   <li>
 *     <b>自定义枚举转换器注册：</b>注册 {@link StringToEnumConverterFactory}，
 *     使 Controller 接收参数时能自动将字符串转换为对应的枚举类型（如 {@link StorageTypeEnum}）。
 *   </li>
 * </ol>
 *
 * @author zhaojun
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 创建自定义的 Servlet Web 服务器工厂，配置 Tomcat 允许 URL 中包含特殊字符。
     * <p>
     * 默认情况下 Tomcat 对 URL 中的字符有严格的限制，但网盘系统中文件名可能包含
     * 方括号、管道符等特殊字符。通过设置 {@code relaxedPathChars} 和
     * {@code relaxedQueryChars} 属性来放宽限制。
     * </p>
     *
     * @return 配置了特殊字符支持的 ServletWebServerFactory 实例
     */
    @Bean
    public ServletWebServerFactory webServerFactory() {
        TomcatServletWebServerFactory webServerFactory = new TomcatServletWebServerFactory();

        // 添加对 URL 路径和查询参数中特殊符号的支持
        webServerFactory.addConnectorCustomizers(connector -> {
            // 设置 URL 路径中允许的特殊字符
            connector.setProperty("relaxedPathChars", "<>[\\]^`{|}%[]");
            // 设置 URL 查询参数中允许的特殊字符
            connector.setProperty("relaxedQueryChars", "<>[\\]^`{|}%[]");
        });
        return webServerFactory;
    }

    /**
     * 添加自定义格式化器（Converter），将 {@link StringToEnumConverterFactory} 注册到 Spring MVC 中。
     * <p>
     * 注册后，Controller 方法参数中的枚举类型（如通过 @RequestParam 或路径变量传入的字符串）
     * 会自动使用此工厂完成字符串到枚举的转换。
     * </p>
     *
     * @param registry 格式化器注册表
     * @see StorageTypeEnum
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        // 注册自定义的 String -> 枚举转换器工厂
        registry.addConverterFactory(new StringToEnumConverterFactory());
    }

}

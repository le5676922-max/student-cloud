package io.studentcloud.platform;

import io.studentcloud.runtime.ServiceRuntimeConfiguration;
import io.studentcloud.clients.ServiceClientConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** 启动账号、空间管理和人工服务所属的平台服务。 */
@SpringBootApplication(scanBasePackages="io.studentcloud.platform")
@Import({ServiceRuntimeConfiguration.class,ServiceClientConfiguration.class})
public class PlatformApplication {
    /** 加载平台服务配置并启动 Spring Boot 应用。 */
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class,args); }
}

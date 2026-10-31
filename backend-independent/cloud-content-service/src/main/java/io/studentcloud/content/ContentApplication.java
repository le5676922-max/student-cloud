package io.studentcloud.content;

import io.studentcloud.clients.IdentityClientConfiguration;
import io.studentcloud.runtime.ServiceRuntimeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 启动承载云盘、课程资料与学习库接口的内容微服务。
 * File and course microservice; owns neither identity tables nor the assistant database.
 */
@SpringBootApplication(scanBasePackages = "io.studentcloud.content",
        excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Import({ServiceRuntimeConfiguration.class, IdentityClientConfiguration.class})
public class ContentApplication {
    public static void main(String[] args) { SpringApplication.run(ContentApplication.class, args); }
}

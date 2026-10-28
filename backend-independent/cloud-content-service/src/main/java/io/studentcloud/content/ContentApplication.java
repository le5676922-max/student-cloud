package io.studentcloud.content;

import io.studentcloud.clients.IdentityClientConfiguration;
import io.studentcloud.runtime.ServiceRuntimeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** File and course microservice; owns neither identity tables nor the assistant database. */
@SpringBootApplication(scanBasePackages = "io.studentcloud.content",
        excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Import({ServiceRuntimeConfiguration.class, IdentityClientConfiguration.class})
public class ContentApplication {
    public static void main(String[] args) { SpringApplication.run(ContentApplication.class, args); }
}

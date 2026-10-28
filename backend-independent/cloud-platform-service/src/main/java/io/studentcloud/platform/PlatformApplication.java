package io.studentcloud.platform;

import io.studentcloud.runtime.ServiceRuntimeConfiguration;
import io.studentcloud.clients.ServiceClientConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages="io.studentcloud.platform")
@Import({ServiceRuntimeConfiguration.class,ServiceClientConfiguration.class})
public class PlatformApplication {
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class,args); }
}

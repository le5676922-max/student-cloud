package io.studentcloud.intelligence;

import io.studentcloud.clients.IdentityClientConfiguration;
import io.studentcloud.runtime.ServiceRuntimeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Intelligence owns its memory, review knowledge and task database; identity/content remain remote. */
@SpringBootApplication(scanBasePackages="io.studentcloud.intelligence")
@Import({ServiceRuntimeConfiguration.class, IdentityClientConfiguration.class})
@EnableScheduling
public class IntelligenceApplication {
    public static void main(String[] args) { SpringApplication.run(IntelligenceApplication.class, args); }
}

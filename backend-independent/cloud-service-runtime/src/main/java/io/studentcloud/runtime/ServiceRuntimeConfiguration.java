package io.studentcloud.runtime;

import io.studentcloud.runtime.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

/** Explicit shared HTTP infrastructure; no account/drive/course implementation is scanned. */
@Configuration(proxyBeanMethods=false)
@Import({AuthenticationFilter.class,AuthenticationBudgetFilter.class,UploadBudgetFilter.class,HttpFailures.class,
        UnimplementedApiController.class,InternalServiceKeyFilter.class,TrustedClientAddress.class,WebConfiguration.class,RuntimeHealthController.class})
public class ServiceRuntimeConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
}

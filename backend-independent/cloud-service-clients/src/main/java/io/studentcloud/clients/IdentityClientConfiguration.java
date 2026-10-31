package io.studentcloud.clients;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/** 装配远程身份查询端口，避免业务服务依赖账号持久化实现。 */
@Configuration(proxyBeanMethods=false)
@Import(ServiceClientConfiguration.class)
public class IdentityClientConfiguration {
    @Bean RemoteIdentityClient remoteIdentityClient(@Qualifier("serviceRestClientBuilder") RestClient.Builder builder,CircuitBreakerFactory<?,?> breakers){
        return new RemoteIdentityClient(builder.build(),breakers);
    }
}

package io.studentcloud.platform.config;

import io.studentcloud.account.WorkspaceOwnershipTransferService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Disabled until explicit acceptance; absent controllers do not expose a dormant write endpoint. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="student-cloud.ownership-transfer.enabled",havingValue="true")
public class OwnershipTransferConfiguration {
    @Bean @DependsOn("platformSchema")
    WorkspaceOwnershipTransferService workspaceOwnershipTransfers(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){
        return new WorkspaceOwnershipTransferService(jdbc,manager,clock,true);
    }
}

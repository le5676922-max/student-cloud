package io.studentcloud.content.config;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.course.CourseOwnershipTransferService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="student-cloud.ownership-transfer.enabled",havingValue="true")
public class OwnershipTransferConfiguration {
    @Bean @DependsOn("independentSchema")
    CourseOwnershipTransferService courseOwnershipTransfers(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts){
        return new CourseOwnershipTransferService(jdbc,manager,clock,accounts,true);
    }
}

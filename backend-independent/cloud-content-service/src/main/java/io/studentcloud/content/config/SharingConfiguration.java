package io.studentcloud.content.config;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.content.sharing.ControlledShareService;
import io.studentcloud.course.SharedCourseService;
import io.studentcloud.drive.application.MountService;
import java.time.Clock;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
public class SharingConfiguration {
    @Bean @DependsOn("independentSchema")
    ControlledShareService controlledShares(JdbcTemplate jdbc,PlatformTransactionManager transactions,Clock clock,
            AccountDirectory accounts,MountService mounts,SharedCourseService courses,
            @Value("${student-cloud.storage.managed-root:}") String managedRoot){
        return new ControlledShareService(jdbc,transactions,clock,accounts,mounts,courses,managedRoot.isBlank()?null:Path.of(managedRoot));
    }
}

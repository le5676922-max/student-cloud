package io.studentcloud.content.config;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.content.application.AgentCommandService;
import io.studentcloud.drive.application.MountService;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
@Configuration(proxyBeanMethods=false)
public class AgentCommandConfiguration {
    @Bean @DependsOn("independentSchema") AgentCommandService agentCommands(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,AccountDirectory accounts,MountService mounts,ObjectMapper json,io.studentcloud.content.learning.LearningLibraryService learning){return new AgentCommandService(jdbc,tx,clock,accounts,mounts,json,learning);}
}

package io.studentcloud.platform.config;

import io.studentcloud.account.*;
import io.studentcloud.support.SupportService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
public class PlatformServicesConfiguration {
    @Bean @DependsOn("platformSchema")
    AccountService accounts(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock) {
        return new AccountService(jdbc,new TransactionTemplate(tx),clock,Duration.ofHours(24));
    }
    @Bean @DependsOn("platformSchema")
    AccountAdministration accountAdministration(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,WorkspaceEnrollmentService enrollment) {
        return new AccountAdministration(jdbc,tx,clock,enrollment::assertMemberActivationAllowed);
    }
    @Bean @DependsOn("platformSchema")
    WorkspaceMemberQuery workspaceMemberQuery(JdbcTemplate jdbc,AccountAdministration accounts){return new WorkspaceMemberQuery(jdbc,accounts);}
    @Bean @DependsOn("platformSchema")
    PlatformAdministrationService platformAdministration(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock){return new PlatformAdministrationService(jdbc,tx,clock);}
    @Bean @DependsOn("platformSchema")
    AccountDirectory accountDirectory(JdbcTemplate jdbc) { return new JdbcAccountDirectory(jdbc); }
    @Bean SessionAuthenticator sessionAuthenticator(AccountService accounts) { return accounts::authenticate; }
    @Bean @DependsOn("platformSchema")
    SupportService support(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock) { return new SupportService(jdbc,tx,clock); }
}

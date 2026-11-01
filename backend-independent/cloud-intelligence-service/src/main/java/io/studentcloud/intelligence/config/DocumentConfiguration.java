package io.studentcloud.intelligence.config;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.assistant.documents.DocumentProjectionLifecycle;
import io.studentcloud.assistant.documents.DocumentResearchService;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Safe default: do not manufacture immutable source IDs from paths or consume document jobs.
 * A's versioned ContentFacts / event / parser HTTP ports must replace this unavailable adapter
 * before private indexing is enabled. No cross-database access or external model is installed.
 */
@Configuration(proxyBeanMethods=false)
public class DocumentConfiguration {
    @Bean @DependsOn("intelligenceSchema")
    DocumentProjectionLifecycle documentProjectionLifecycle(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock) {
        return new DocumentProjectionLifecycle(jdbc,tx,clock);
    }
    @Bean
    DocumentResearchService documentResearch(AccountDirectory accounts) {
        return new DocumentResearchService(accounts,DocumentResearchService.unavailableFacts(),
                (actor,facts,limit)->List.of(),null);
    }
}

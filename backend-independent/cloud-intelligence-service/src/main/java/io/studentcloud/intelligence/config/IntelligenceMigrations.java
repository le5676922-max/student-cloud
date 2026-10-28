package io.studentcloud.intelligence.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** The intelligence migration history contains only intelligence-owned tables. */
@Configuration(proxyBeanMethods=false)
public class IntelligenceMigrations {
    @Bean(name="intelligenceSchema", initMethod="migrate")
    Flyway intelligenceSchema(DataSource database) {
        return Flyway.configure().dataSource(database).locations("classpath:db/intelligence-migrations")
                .baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true)
                .javaMigrations(new Script("1", "intelligence_tasks", "db/independent-tasks-schema.sql"),
                        new Script("2", "intelligence_knowledge", "db/independent-assistant-schema.sql"),
                        new Script("3", "intelligence_chat_answers", "db/independent-chat-schema.sql"),
                        new Script("4", "intelligence_agent_plans", "db/independent-agent-schema.sql"),
                        new Script("5", "intelligence_agent_schedules", "db/independent-agent-schedules-schema.sql"),
                        new Script("6", "intelligence_answer_provenance", "db/independent-answer-provenance-schema.sql"),
                        new Script("7", "intelligence_agent_plan_budget", "db/independent-agent-plan-budget-schema.sql"))
                .load();
    }

    private record Script(String version, String description, String path) implements JavaMigration {
        @Override public MigrationVersion getVersion() { return MigrationVersion.fromVersion(version); }
        @Override public String getDescription() { return description; }
        @Override public boolean canExecuteInTransaction() { return true; }
        @Override public Integer getChecksum() {
            try (var input = new ClassPathResource(path).getInputStream()) {
                CRC32 checksum = new CRC32();
                checksum.update(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
                return (int) checksum.getValue();
            } catch (IOException failure) { throw new UncheckedIOException("Intelligence migration is unavailable", failure); }
        }
        @Override public void migrate(Context context) throws Exception {
            ScriptUtils.executeSqlScript(context.getConnection(), new ClassPathResource(path));
        }
    }
}

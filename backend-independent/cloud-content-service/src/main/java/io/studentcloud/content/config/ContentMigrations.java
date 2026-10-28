package io.studentcloud.content.config;

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

@Configuration(proxyBeanMethods = false)
public class ContentMigrations {
    @Bean(name = "independentSchema", initMethod = "migrate")
    Flyway contentSchema(DataSource database) {
        return Flyway.configure().dataSource(database).locations("classpath:db/content-migrations")
                .baselineOnMigrate(false).cleanDisabled(true)
                .javaMigrations(new Migration("1", "content_drives", "db/independent-drives-schema.sql"),
                        new Migration("2", "content_courses", "db/independent-courses-schema.sql"),
                        new Migration("3", "content_materials", "db/independent-course-materials-schema.sql"),
                        new Migration("4", "content_approvals", "db/independent-cloud-access-schema.sql"),
                        new Migration("5", "content_learning", "db/independent-learning-schema.sql"),
                        new Migration("6", "content_agent_commands", "db/independent-agent-commands-schema.sql"),
                        new Migration("7", "content_storage_administration", "db/independent-storage-administration-schema.sql"),
                        new Migration("8", "content_file_catalog", "db/independent-file-catalog-schema.sql"),
                        new Migration("9", "content_resumable_uploads", "db/independent-uploads-schema.sql"),
                        new Migration("10", "content_controlled_shares", "db/independent-shares-schema.sql"),
                        new Migration("11", "content_recycle_bin", "db/independent-recycle-schema.sql"),
                        new Migration("12", "content_remote_storage", "db/independent-remote-storage-schema.sql"),
                        new Migration("13", "content_remote_writes", "db/independent-remote-writes-schema.sql"))
                .load();
    }

    private record Migration(String version, String description, String path) implements JavaMigration {
        @Override public MigrationVersion getVersion() { return MigrationVersion.fromVersion(version); }
        @Override public String getDescription() { return description; }
        @Override public boolean canExecuteInTransaction() { return true; }
        @Override public Integer getChecksum() {
            try (var input = new ClassPathResource(path).getInputStream()) {
                CRC32 checksum = new CRC32();
                checksum.update(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
                return (int) checksum.getValue();
            } catch (IOException missing) { throw new UncheckedIOException("Content migration is unavailable", missing); }
        }
        @Override public void migrate(Context context) throws Exception {
            ScriptUtils.executeSqlScript(context.getConnection(), new ClassPathResource(path));
        }
    }
}

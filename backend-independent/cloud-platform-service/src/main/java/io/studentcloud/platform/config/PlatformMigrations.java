package io.studentcloud.platform.config;

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

/** Platform owns identity and human support only; content and AI migrations are absent from its classpath. */
@Configuration(proxyBeanMethods=false)
public class PlatformMigrations {
    @Bean(name="platformSchema",initMethod="migrate") Flyway platformSchema(DataSource database) {
        return Flyway.configure().dataSource(database).locations("classpath:db/platform-migrations")
                .baselineOnMigrate(false).cleanDisabled(true)
                .javaMigrations(new Schema("1","identity","db/independent-schema.sql"),new Schema("2","support","db/independent-support-schema.sql"),
                        new Schema("3","email_challenges","db/independent-email-schema.sql"),
                        new Schema("4","workspace_enrollment","db/independent-enrollment-schema.sql"),
                        new Schema("5","public_profiles","db/independent-public-profile-schema.sql"),
                        new Schema("6","platform_administration","db/independent-platform-administration-schema.sql")).load();
    }
    private record Schema(String version,String description,String path) implements JavaMigration {
        @Override public MigrationVersion getVersion() { return MigrationVersion.fromVersion(version); }
        @Override public String getDescription() { return description; }
        @Override public boolean canExecuteInTransaction() { return true; }
        @Override public Integer getChecksum() {
            try(var input=new ClassPathResource(path).getInputStream()) {
                var crc=new CRC32();crc.update(new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8));return (int)crc.getValue();
            }catch(IOException missing){throw new UncheckedIOException(missing);}
        }
        @Override public void migrate(Context context) throws Exception {ScriptUtils.executeSqlScript(context.getConnection(),new ClassPathResource(path));}
    }
}

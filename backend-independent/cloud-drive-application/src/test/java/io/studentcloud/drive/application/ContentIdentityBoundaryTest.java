package io.studentcloud.drive.application;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class ContentIdentityBoundaryTest {
    @TempDir Path temporary;

    @Test void driveOperationsNeedNoIdentityTableInTheContentDatabase() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:content_boundary_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql")).execute(source);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            Actor administrator = new Actor(42, "SC42", "synthetic", null, Role.ADMIN, null);
            AccountDirectory directory = new AccountDirectory() {
                @Override public Actor account(long id) {
                    if (id != 42) throw new ApplicationFault("42000", 401, "账号不可用");
                    return administrator;
                }
                @Override public boolean workspaceEnabled(long id) { return id == 7; }
            };
            Path managed = Files.createDirectory(temporary.resolve("managed"));
            Path physical = Files.createDirectory(managed.resolve("drive"));
            MountService mounts = new MountService(jdbc, new DataSourceTransactionManager(source), Clock.systemUTC(), managed, directory);
            mounts.createLocal(administrator, "Isolated", "isolated", physical, 7L);
            mounts.upload(administrator, "isolated", DrivePath.of("/proof.txt"), new ByteArrayInputStream(new byte[] {1,2}), 2);
            assertEquals(1, mounts.list(administrator, "isolated", DrivePath.of("/")).size());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('SC_ACCOUNT','SC_WORKSPACE','SC_PLATFORM_SETTINGS')", Integer.class));
        } finally { new JdbcTemplate(source).execute("SHUTDOWN"); }
    }
}

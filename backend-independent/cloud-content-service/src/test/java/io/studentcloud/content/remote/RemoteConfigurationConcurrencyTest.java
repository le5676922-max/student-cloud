package io.studentcloud.content.remote;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class RemoteConfigurationConcurrencyTest {
    @Test
    void saveWaitsForTheMountThenRejectsAWriteRegisteredByTheLockOwner() throws Exception {
        var database = new DriverManagerDataSource("jdbc:h2:mem:remote_config_race_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),
                new ClassPathResource("db/independent-storage-administration-schema.sql"),
                new ClassPathResource("db/independent-remote-storage-schema.sql"),
                new ClassPathResource("db/independent-remote-writes-schema.sql")).execute(database);

        var mountLockRequested = new CountDownLatch(1);
        var jdbc = new JdbcTemplate(database) {
            @Override
            public Map<String, Object> queryForMap(String sql, Object... args) {
                if (sql.equals("SELECT * FROM sc_drive_mount WHERE id=? FOR UPDATE")) {
                    mountLockRequested.countDown();
                }
                return super.queryForMap(sql, args);
            }
        };
        var transactions = new DataSourceTransactionManager(database);
        var admin = new Actor(2, "admin", "admin", "admin@example.test", Role.TENANT_ADMIN, 20L);
        AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) {
                if (id != admin.id()) throw new IllegalArgumentException();
                return admin;
            }
            public boolean workspaceEnabled(long id) { return id == 20; }
        };
        var service = new RemoteStorageAdministration(jdbc, transactions, Clock.systemUTC(), accounts,
                id -> List.of(admin), connection -> {
                    throw new AssertionError("A disabled configuration must not perform provider IO");
                }, Base64.getEncoder().encodeToString(new byte[32]), "");
        long id = service.save(admin, Map.of("name", "original", "key", "remote-race", "type", "aliyun",
                "tenantId", 20L, "enable", false, "storageSourceAllParam", Map.of(
                        "endPoint", "https://oss-cn-hangzhou.aliyuncs.com", "bucketName", "student-test",
                        "region", "cn-hangzhou", "basePath", "race", "accessKey", "access-id",
                        "secretKey", "secret-value")));
        var update = new LinkedHashMap<>(service.detail(admin, id));
        update.put("type", "aliyun");
        update.put("name", "must-not-commit");
        String pendingOperation = UUID.randomUUID().toString();
        var saving = new FutureTask<>(() -> assertThrows(ApplicationFault.class, () -> service.save(admin, update)));

        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE", Long.class, id);
                executor.execute(saving);
                try {
                    assertTrue(mountLockRequested.await(5, TimeUnit.SECONDS),
                            "Configuration save must lock the mount before observing the write guard");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(saving.isDone(), "Save must wait while another connection owns the mount lock");
                jdbc.update("INSERT INTO sc_remote_write_guard(mount_id,active_operation) VALUES(?,?)",
                        id, pendingOperation);
            });
            assertEquals("REMOTE_WRITE_PENDING", saving.get(5, TimeUnit.SECONDS).code());
        }

        assertEquals("original", jdbc.queryForObject("SELECT name FROM sc_drive_mount WHERE id=?", String.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM sc_drive_mount WHERE id=?", Long.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT config_version FROM sc_remote_storage WHERE mount_id=?", Long.class, id));
        assertEquals(pendingOperation, jdbc.queryForObject(
                "SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=?", String.class, id));
        assertEquals("secret-value", service.load(id).connection().secretKey());
    }
}

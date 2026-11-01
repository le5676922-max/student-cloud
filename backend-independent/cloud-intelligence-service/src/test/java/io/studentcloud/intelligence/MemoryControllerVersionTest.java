package io.studentcloud.intelligence;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.assistant.MemoryService;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.http.MemoryController;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class MemoryControllerVersionTest {
    private final Actor owner = new Actor(2, "SC_OWNER", "owner", null, Role.USER, 7L);
    private final Actor other = new Actor(3, "SC_OTHER", "other", null, Role.TENANT_ADMIN, 7L);
    private MemoryService memories;
    private MemoryController controller;

    @BeforeEach void setUp() {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:memory_version_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql")).execute(database);
        var accounts = new AccountDirectory() {
            public Actor account(long id) { return id == owner.id() ? owner : other; }
            public boolean workspaceEnabled(long id) { return id == 7; }
        };
        memories = new MemoryService(new JdbcTemplate(database), new DataSourceTransactionManager(database), Clock.systemUTC(), accounts);
        controller = new MemoryController(memories);
    }

    private HttpServletRequest request(Actor actor) {
        return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> method.getName().equals("getAttribute") && Actor.class.getName().equals(args[0]) ? actor : null);
    }

    @Test void missingDeleteVersionCannotDeleteAnotherWindowsNewerMemory() {
        var first = memories.create(owner, "original");
        memories.update(owner, first.id(), 1, "newer content");
        assertEquals(400, assertThrows(ApplicationFault.class, () -> controller.delete(first.id(), null, request(owner))).httpStatus());
        assertEquals("newer content", memories.list(owner).getFirst().content());
    }

    @Test void contentOnlyUpdateCannotBypassAnAbaVersionChange() {
        var first = memories.create(owner, "A");
        memories.update(owner, first.id(), 1, "B");
        memories.update(owner, first.id(), 2, "A");
        assertEquals(400, assertThrows(ApplicationFault.class, () -> controller.update(first.id(),
                new MemoryController.MemoryInput("old window edit", null, "A"), request(owner))).httpStatus());
        assertEquals(3, memories.list(owner).getFirst().version());
    }

    @Test void readVersionRejectsStaleDeleteAndAbaUpdateWithoutLosingCurrentMemory() {
        var first = memories.create(owner, "A");
        memories.update(owner, first.id(), 1, "B");
        memories.update(owner, first.id(), 2, "A");
        assertEquals(409, assertThrows(ApplicationFault.class, () -> controller.delete(first.id(), 1L, request(owner))).httpStatus());
        assertEquals(409, assertThrows(ApplicationFault.class, () -> controller.update(first.id(),
                new MemoryController.MemoryInput("old window edit", 1L, "A"), request(owner))).httpStatus());
        assertEquals("A", memories.list(owner).getFirst().content());
        assertEquals(3, memories.list(owner).getFirst().version());
    }

    @Test void currentVersionAllowsEditAndDisabledMemoryDeletionButNeverCrossAccountAccess() {
        var first = memories.create(owner, "A");
        assertEquals(404, assertThrows(ApplicationFault.class, () -> controller.delete(first.id(), 1L, request(other))).httpStatus());
        var updated = controller.update(first.id(), new MemoryController.MemoryInput("B", 1L, null), request(owner)).data();
        assertEquals(2, updated.items().getFirst().version());
        memories.enable(owner, false);
        assertTrue(controller.delete(first.id(), 2L, request(owner)).data().items().isEmpty());
    }
}

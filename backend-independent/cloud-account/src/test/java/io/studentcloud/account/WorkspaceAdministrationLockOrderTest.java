package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Real row-lock checks: waiting for a workspace must never already hold its administrator. */
class WorkspaceAdministrationLockOrderTest {
    private DataSource database;
    private Actor owner;
    private enum EntryPoint { MEMBERS, INVITATIONS }

    @BeforeEach void setup() {
        database=new DriverManagerDataSource("jdbc:h2:mem:workspace_lock_"+UUID.randomUUID()
                +";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),
                new ClassPathResource("db/independent-enrollment-schema.sql")).execute(database);
        var jdbc=new JdbcTemplate(database);
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'First',TRUE,0),(2,'Second',TRUE,0)");
        jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(1,'SCFIXTURE1','owner','unused','TENANT_ADMIN',1,TRUE,1,0)");
        owner=new Actor(1,"SCFIXTURE1","owner",null,Role.TENANT_ADMIN,1L);
    }

    @ParameterizedTest @EnumSource(EntryPoint.class)
    void workspaceWaitLeavesAdministratorUnlockedForOwnershipTransfer(EntryPoint entry) throws Exception {
        var workspaceAttempt=new CountDownLatch(1);
        DataSource observed=new ObservedDataSource(database,workspaceAttempt);
        try(var blocker=database.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            blockWorkspace(blocker);
            var request=pool.submit(()->invoke(entry,observed));
            try {
                assertTrue(workspaceAttempt.await(3,TimeUnit.SECONDS),"request must reach its workspace lock");
                try(var probe=database.getConnection();var sql=probe.createStatement()) {
                    probe.setAutoCommit(false);sql.execute("SET LOCK_TIMEOUT 100");
                    assertDoesNotThrow(()->{
                        try(var row=sql.executeQuery("SELECT id FROM sc_account WHERE id=1 FOR UPDATE")) {
                            assertTrue(row.next());
                        }
                    },"a handover holding the workspace must still be able to lock its administrator");
                    probe.rollback();
                }
            } finally { blocker.rollback();request.get(5,TimeUnit.SECONDS); }
        }
    }

    @ParameterizedTest @EnumSource(EntryPoint.class)
    void changedWorkspaceWhileWaitingRejectsTheStaleCallerAfterLock(EntryPoint entry) throws Exception {
        var workspaceAttempt=new CountDownLatch(1);
        DataSource observed=new ObservedDataSource(database,workspaceAttempt);
        try(var blocker=database.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            blockWorkspace(blocker);
            var request=pool.submit(()->invoke(entry,observed));
            try {
                assertTrue(workspaceAttempt.await(3,TimeUnit.SECONDS));
                try(var change=database.getConnection();var sql=change.createStatement()) {
                    sql.execute("SET LOCK_TIMEOUT 100");
                    assertEquals(1,assertDoesNotThrow(()->sql.executeUpdate("UPDATE sc_account SET workspace_id=2 WHERE id=1")));
                }
            } finally { blocker.rollback(); }
            var rejected=assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ApplicationFault.class,rejected.getCause());
            assertEquals(403,((ApplicationFault)rejected.getCause()).httpStatus());
        }
    }

    private static void blockWorkspace(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try(var sql=connection.createStatement();var row=sql.executeQuery("SELECT id FROM sc_workspace WHERE id=1 FOR UPDATE")) {
            assertTrue(row.next());
        }
    }
    private Object invoke(EntryPoint entry,DataSource source) {
        var jdbc=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);
        return entry==EntryPoint.MEMBERS
                ?new AccountAdministration(jdbc,manager,Clock.systemUTC()).listWorkspaceMembers(owner,0,20,"")
                :new WorkspaceEnrollmentService(jdbc,manager,Clock.systemUTC()).listInvitations(owner);
    }

    /** Observe query submission only; all JDBC calls and lock effects still run against the real DB. */
    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource target;
        private final CountDownLatch workspaceAttempt;
        ObservedDataSource(DataSource target,CountDownLatch workspaceAttempt) {
            this.target=target;this.workspaceAttempt=workspaceAttempt;
        }
        @Override public Connection getConnection() throws SQLException { return observe(target.getConnection()); }
        @Override public Connection getConnection(String username,String password) throws SQLException {
            return observe(target.getConnection(username,password));
        }
        private Connection observe(Connection real) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql
                        &&sql.contains("FROM sc_workspace WHERE id=?")&&sql.contains("FOR UPDATE"))workspaceAttempt.countDown();
                try { return method.invoke(real,args); }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}

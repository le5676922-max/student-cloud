package io.studentcloud.drive.application;

import io.studentcloud.account.Actor;
import io.studentcloud.account.JdbcAccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MountServiceTest {
    @TempDir Path temporary;
    private Path managed;
    private Path mounted;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private MountService service;
    private final Actor admin = actor(1, Role.ADMIN, null);
    private final Actor tenant = actor(2, Role.TENANT_ADMIN, 1L);
    private final Actor member = actor(3, Role.USER, 1L);
    private final Actor foreign = actor(4, Role.USER, 2L);

    @BeforeEach void initialize() throws IOException {
        managed = Files.createDirectory(temporary.resolve("managed"));
        mounted = Files.createDirectory(managed.resolve("one"));
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:drive_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),
                new ClassPathResource("db/independent-drives-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        service = new MountService(jdbc, transactions, Clock.systemUTC(), managed, new JdbcAccountDirectory(jdbc));
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'one',TRUE,1),(2,'two',TRUE,1)");
        seedAccount(admin); seedAccount(tenant); seedAccount(member); seedAccount(foreign);
    }

    @Test void administratorCreatesLocalMountAndPublicViewContainsNoHostRoot() {
        MountView view = mount();
        assertEquals("LOCAL", view.engine());
        assertEquals(1L, view.ownerId());
        assertEquals(1L, view.workspaceId());
        assertTrue(view.permissions().contains("delete"));
        assertFalse(view.toString().contains(managed.toString()));
        assertEquals(1, service.listMounts(admin).size());
    }

    @Test void missingManagedRootDisablesConnectionFeatures() {
        MountService disabled = new MountService(jdbc, transactions, Clock.systemUTC(), null, new JdbcAccountDirectory(jdbc));
        assertStatus(503, () -> disabled.createLocal(admin, "one", "drive", mounted));
        assertStatus(503, () -> disabled.listMounts(admin));
        assertTrue(disabled.listLocalMounts(admin).isEmpty());
    }

    @Test void mountCannotUseOutsideOrMissingPhysicalDirectory() {
        assertStatus(403, () -> service.createLocal(admin, "outside", "outside", temporary));
        assertStatus(409, () -> service.createLocal(admin, "missing", "missing", managed.resolve("missing")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount", Integer.class));
    }

    @Test void managedAllocationRootCannotItselfBecomeABrowsableMount() {
        assertStatus(400, () -> service.createLocal(admin, "all", "all-data", managed));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount", Integer.class));
    }

    @Test void equalAndNestedMountRootsAreRejectedButSiblingRootsAreAllowed() throws IOException {
        mount();
        Path nested = Files.createDirectory(mounted.resolve("nested"));
        assertStatus(409, () -> service.createLocal(admin, "duplicate", "duplicate", mounted));
        assertStatus(409, () -> service.createLocal(admin, "nested", "nested", nested));
        Path sibling = Files.createDirectory(managed.resolve("sibling"));
        service.createLocal(admin, "sibling", "sibling", sibling);
        assertEquals(2, service.listMounts(admin).size());
    }

    @Test void parentOfAnExistingMountCannotBecomeAnotherMount() throws IOException {
        Path nested = Files.createDirectory(mounted.resolve("nested"));
        service.createLocal(admin, "nested", "nested", nested);
        assertStatus(409, () -> service.createLocal(admin, "parent", "parent", mounted));
    }

    @Test void rootSymlinkIsRejectedWithoutFollowingIt() throws IOException {
        Path link = managed.resolve("link");
        try { Files.createSymbolicLink(link, mounted); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic-link privilege is unavailable");
        }
        assertStatus(409, () -> service.createLocal(admin, "link", "linked", link));
    }

    @Test void tenantAndMemberCannotCreateLocalConnections() {
        assertStatus(403, () -> service.createLocal(tenant, "one", "drive", mounted));
        assertStatus(403, () -> service.createLocal(member, "one", "drive", mounted));
    }

    @Test void suppliedRoleCannotElevateCurrentDatabaseIdentity() {
        Actor forged = actor(member.id(), Role.ADMIN, null);
        assertStatus(403, () -> service.createLocal(forged, "one", "drive", mounted));
    }

    @Test void duplicateKeyReturnsSafeConflictWithoutInternalSql() {
        mount();
        ApplicationFault fault = assertThrows(ApplicationFault.class,
                () -> service.createLocal(admin, "one", "drive", mounted));
        assertEquals(409, fault.httpStatus());
        assertFalse(fault.getMessage().contains("INSERT"));
        assertFalse(fault.getMessage().contains(managed.toString()));
    }

    @Test void membersNeedAnExplicitGrantEvenForTheirWorkspaceMount() {
        mount();
        assertTrue(service.listMounts(member).isEmpty());
        assertStatus(403, () -> service.list(member, "drive", p("/")));
        assertStatus(403, () -> service.list(tenant, "drive", p("/")));
    }

    @Test void anotherWorkspaceCannotUseASeededGrant() throws IOException {
        MountView view = mount();
        grant(foreign, view.id(), "/members/u4", "list,read", null);
        assertTrue(service.listMounts(foreign).isEmpty());
        assertStatus(403, () -> service.list(foreign, "drive", p("/")));
    }

    @Test void memberRootMustBeExactlyItsOwnPersonalDirectory() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/", "list,read", null);
        assertStatus(403, () -> service.list(member, "drive", p("/")));
        jdbc.update("UPDATE sc_drive_grant SET root_path='/members/u4' WHERE user_id=3");
        assertStatus(403, () -> service.list(member, "drive", p("/")));
    }

    @Test void workspaceAdministratorCannotBeGrantedAnotherPersonsDirectory() throws IOException {
        MountView view = mount();
        grant(tenant, view.id(), "/", "list,read", null);
        assertStatus(403, () -> service.list(tenant, "drive", p("/")));
        jdbc.update("UPDATE sc_drive_grant SET root_path='/members/u3' WHERE user_id=2");
        assertStatus(403, () -> service.list(tenant, "drive", p("/")));
        Files.createDirectories(mounted.resolve("admins/u2"));
        jdbc.update("UPDATE sc_drive_grant SET root_path='/admins/u2' WHERE user_id=2");
        assertTrue(service.list(tenant, "drive", p("/")).isEmpty());
    }

    @Test void ownershipRoleChangesKeepBothUsersOriginalPrivateRoots() throws IOException {
        MountView view = mount();
        grant(tenant, view.id(), "/admins/u2", "list,read,upload", null);
        grant(member, view.id(), "/members/u3", "list,read,upload", null);
        Files.writeString(mounted.resolve("admins/u2/old-admin.txt"), "old admin");
        Files.writeString(mounted.resolve("members/u3/new-admin.txt"), "new admin");
        jdbc.update("UPDATE sc_account SET role='USER' WHERE id=2");
        jdbc.update("UPDATE sc_account SET role='TENANT_ADMIN' WHERE id=3");

        assertEquals(1, service.listMounts(tenant).size());
        assertEquals(1, service.listMounts(member).size());
        try (ReadHandle data = service.readWithMetadata(tenant, "drive", p("/old-admin.txt"))) {
            assertEquals("old admin", new String(data.input().readAllBytes(), StandardCharsets.UTF_8));
        }
        try (ReadHandle data = service.readWithMetadata(member, "drive", p("/new-admin.txt"))) {
            assertEquals("new admin", new String(data.input().readAllBytes(), StandardCharsets.UTF_8));
        }
        service.upload(tenant, "drive", p("/after.txt"), new ByteArrayInputStream(new byte[]{1}), 1);
        assertTrue(Files.isRegularFile(mounted.resolve("admins/u2/after.txt")));
        assertTrue(service.stat(member, "drive", p("/old-admin.txt")).isEmpty());
        assertTrue(service.stat(tenant, "drive", p("/new-admin.txt")).isEmpty());
        jdbc.update("UPDATE sc_drive_grant SET root_path='/members/u3' WHERE user_id=2");
        assertStatus(403, () -> service.list(tenant, "drive", p("/")));
        jdbc.update("UPDATE sc_drive_grant SET root_path='/admins/u2' WHERE user_id=3");
        assertStatus(403, () -> service.list(member, "drive", p("/")));
    }

    @Test void personalRootReallyConfinesIoToMembersDirectory() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/members/u3", "list,read,upload,mkdir", null);
        Files.writeString(mounted.resolve("admin-only"), "private");
        Files.writeString(mounted.resolve("members/u3/own"), "mine");
        assertEquals(java.util.List.of("own"), service.list(member, "drive", p("/")).stream().map(io.studentcloud.drive.FileRecord::name).toList());
        try (ReadHandle data = service.readWithMetadata(member, "drive", p("/own"))) {
            assertEquals("mine", new String(data.input().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(service.stat(member, "drive", p("/admin-only")).isEmpty());
    }

    @Test void recognizedPermissionsAreEnforcedPerOperationAndUnknownOnesFailClosed() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/members/u3", "list", null);
        assertEquals(0, service.list(member, "drive", p("/")).size());
        assertStatus(403, () -> service.mkdir(member, "drive", p("/new")));
        assertStatus(403, () -> service.readWithMetadata(member, "drive", p("/missing")));
        jdbc.update("UPDATE sc_drive_grant SET permissions='list,root-everything' WHERE user_id=3");
        assertStatus(403, () -> service.list(member, "drive", p("/")));
    }

    @Test void revocationAndWorkspaceDisableAreReadFreshOnEveryOperation() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/members/u3", "list", null);
        service.list(member, "drive", p("/"));
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=3");
        assertStatus(403, () -> service.list(member, "drive", p("/")));
        jdbc.update("UPDATE sc_drive_grant SET enabled=TRUE WHERE user_id=3");
        jdbc.update("UPDATE sc_workspace SET enabled=FALSE WHERE id=1");
        assertStatus(401, () -> service.list(member, "drive", p("/")));
    }

    @Test void disabledAccountAndDisabledMountCannotReadFiles() {
        mount();
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=1");
        assertStatus(401, () -> service.list(admin, "drive", p("/")));
        jdbc.update("UPDATE sc_account SET enabled=TRUE WHERE id=1");
        jdbc.update("UPDATE sc_drive_mount SET enabled=FALSE");
        assertStatus(403, () -> service.list(admin, "drive", p("/")));
    }

    @Test void quotaRejectsGrowthAndDeletionReleasesMeasuredCapacity() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/members/u3", "list,read,upload,mkdir,delete", 100L);
        assertEquals(0, service.list(member, "drive", p("/")).size());
        service.mkdir(member, "drive", p("/new"));
        service.upload(member, "drive", p("/new/a"), bytes("a".repeat(100)), 100);
        assertStatus(409, () -> service.upload(member, "drive", p("/new/b"), bytes("b"), 1));
        assertFalse(Files.exists(mounted.resolve("members/u3/new/b")));
        service.delete(member, "drive", p("/new/a"));
        service.upload(member, "drive", p("/new/b"), bytes("b"), 1);
        assertEquals(1, Files.size(mounted.resolve("members/u3/new/b")));
    }

    @Test void knownQuotaOverflowFailsPreflightAndDoesNotReadTheUploadStream() throws IOException {
        MountView view=mount();
        grant(member,view.id(),"/members/u3","list,upload",5L);
        Path root=mounted.resolve("members/u3");
        Files.write(root.resolve("existing.bin"),new byte[]{1,2,3,4});
        Files.createDirectory(root.resolve("nested"));
        var fault=assertThrows(ApplicationFault.class,()->service.prepareUpload(member,"drive",p("/nested/new.bin"),2));
        assertEquals("DRIVE_QUOTA_EXCEEDED",fault.code());
        java.io.InputStream unread=new java.io.InputStream(){
            @Override public int read(){fail("Known quota overflow must fail before reading or staging the stream");return -1;}
        };
        assertEquals("DRIVE_QUOTA_EXCEEDED",assertThrows(ApplicationFault.class,()->service.upload(member,"drive",p("/nested/new.bin"),unread,2)).code());
        service.prepareUpload(member,"drive",p("/nested/new.bin"),1);
        assertFalse(Files.exists(root.resolve("nested/new.bin")));
    }

    @Test void directoryCopiesChargeTheWholeTreeAgainstPersonalQuota() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/members/u3", "list,read,upload,mkdir,copy,delete", 10L);
        service.mkdir(member, "drive", p("/source"));
        service.upload(member, "drive", p("/source/a"), bytes("alpha"), 5);
        service.copy(member, "drive", p("/source"), p("/copy"));
        assertStatus(409, () -> service.copy(member, "drive", p("/source"), p("/third")));
        assertFalse(Files.exists(mounted.resolve("members/u3/third")));
        service.delete(member, "drive", p("/copy"));
        service.copy(member, "drive", p("/source"), p("/third"));
        assertEquals("alpha", Files.readString(mounted.resolve("members/u3/third/a")));
    }

    @Test void aStreamReturningOnlyEmptyReadsCannotKeepAnUploadSlotForever() {
        mount();
        java.io.InputStream emptyReads = new java.io.InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] buffer) { return 0; }
        };
        assertStatus(400, () -> service.upload(admin, "drive", p("/no-data"), emptyReads, 1));
        service.upload(admin, "drive", p("/valid"), bytes("ok"), 2);
        assertTrue(service.stat(admin, "drive", p("/no-data")).isEmpty());
    }

    @Test void administratorPerformsRealCrudThroughTheKernelWithoutImplicitOverwrite() throws IOException {
        mount();
        service.mkdir(admin, "drive", p("/folder"));
        service.upload(admin, "drive", p("/folder/a"), bytes("alpha"), 5);
        assertStatus(409, () -> service.upload(admin, "drive", p("/folder/a"), bytes("wrong"), 5));
        service.copy(admin, "drive", p("/folder/a"), p("/folder/b"));
        service.rename(admin, "drive", p("/folder/b"), "renamed");
        service.move(admin, "drive", p("/folder/renamed"), p("/moved"));
        try (ReadHandle handle = service.readWithMetadata(admin, "drive", p("/moved"))) {
            assertEquals(5, handle.metadata().size());
            assertEquals("alpha", new String(handle.input().readAllBytes(), StandardCharsets.UTF_8));
        }
        service.delete(admin, "drive", p("/folder"));
        assertTrue(service.stat(admin, "drive", p("/folder")).isEmpty());
        assertEquals("alpha", Files.readString(mounted.resolve("moved")));
    }

    @Test void uploadRechecksGrantAtEofAndLeavesNoPublishedOrTemporaryFile() throws IOException {
        MountView view = mount();
        grant(member, view.id(), "/members/u3", "list,upload", null);
        var stream = new ByteArrayInputStream(new byte[] {1, 2}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int result = super.read(bytes, offset, length);
                if (result == -1) jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=3");
                return result;
            }
        };
        assertStatus(403, () -> service.upload(member, "drive", p("/new"), stream, 2));
        try (var files = Files.list(mounted.resolve("members/u3"))) { assertEquals(0, files.count()); }
    }

    @Test void invalidRootInDatabaseCannotEscapeTheManagedArea() {
        mount();
        jdbc.update("UPDATE sc_drive_mount SET physical_root=?", temporary.toString());
        ApplicationFault failure = assertThrows(ApplicationFault.class, () -> service.list(admin, "drive", p("/")));
        assertEquals(403, failure.httpStatus());
        assertFalse(failure.getMessage().contains(temporary.toString()));
    }

    @Test void unsupportedEngineDoesNotFallBackToLocalOrLegacyCode() {
        mount();
        jdbc.update("UPDATE sc_drive_mount SET engine='S3'");
        assertStatus(501, () -> service.list(admin, "drive", p("/")));
    }

    private MountView mount() { return service.createLocal(admin, "资料云盘", "drive", mounted, 1L); }
    @Test void ledgerCountsExistingDataAndDoesNotDoubleChargeRecycleRestore() throws Exception {
        enableLedger();
        var mount=mount(); grant(member,mount.id(),"/members/u3","list,read,upload,delete,mkdir,copy",10L);
        Files.writeString(mounted.resolve("members/u3/old.txt"),"old!");
        assertEquals("UNKNOWN",service.quota(member,"drive").usageState());
        service.upload(member,"drive",p("/new.txt"),bytes("new"),3);
        assertEquals(7L,service.quota(member,"drive").usedBytes());
        assertEquals(0,service.quota(member,"drive").reservedBytes());
        var trash=service.recycle(member,"drive",p("/old.txt"));
        assertEquals(7L,service.quota(member,"drive").usedBytes());
        service.restoreRecycled(member,"drive",trash.id());
        assertEquals(7L,service.quota(member,"drive").usedBytes());
        trash=service.recycle(member,"drive",p("/old.txt"));service.reconcileQuota(member,"drive");service.purgeRecycled(member,"drive",trash.id());
        assertEquals("ESTIMATED",service.quota(member,"drive").usageState());
        assertEquals(3L,service.reconcileQuota(member,"drive").usedBytes());
        assertStatus(403,()->service.quota(foreign,"drive"));
        jdbc.update("UPDATE sc_drive_grant SET quota_bytes=2,revision=revision+1 WHERE user_id=3");
        assertStatus(409,()->service.upload(member,"drive",p("/denied.txt"),bytes("x"),1));
        assertEquals("new",Files.readString(mounted.resolve("members/u3/new.txt")));
    }

    @Test void ledgerRetainsReservationWhenSqlFailsAfterFilesystemPublication() throws Exception {
        enableLedger();var mount=mount();grant(member,mount.id(),"/members/u3","list,read,upload",10L);
        service.reconcileQuota(member,"drive");
        jdbc.execute("DROP TABLE sc_file_catalog_mount");
        assertThrows(ApplicationFault.class,()->service.upload(member,"drive",p("/published.txt"),bytes("yes"),3));
        assertEquals("yes",Files.readString(mounted.resolve("members/u3/published.txt")));
        assertEquals(3,service.quota(member,"drive").reservedBytes());
        assertStatus(409,()->service.reconcileQuota(member,"drive"));
    }

    @Test void purgeWhoseSqlCompletionFailsCannotLeaveAnExactUsageClaim() throws Exception {
        enableLedger();var mount=mount();grant(member,mount.id(),"/members/u3","list,read,upload,delete",10L);
        service.upload(member,"drive",p("/notes"),bytes("yes"),3);
        var trash=service.recycle(member,"drive",p("/notes"));service.reconcileQuota(member,"drive");
        assertEquals("EXACT",service.quota(member,"drive").usageState());
        jdbc.execute("DROP TABLE sc_file_catalog_mount");
        assertThrows(ApplicationFault.class,()->service.purgeRecycled(member,"drive",trash.id()));
        assertEquals("ESTIMATED",service.quota(member,"drive").usageState());
    }

    @Test void scheduledRetentionInvalidatesExactAccountingBeforeRemovingBytes() throws Exception {
        enableLedger();var mount=mount();grant(member,mount.id(),"/members/u3","list,read,upload,delete",10L);
        service.upload(member,"drive",p("/expired"),bytes("old"),3);
        var trash=service.recycle(member,"drive",p("/expired"));service.reconcileQuota(member,"drive");
        jdbc.update("UPDATE sc_drive_trash SET expires_at=0 WHERE id=?",trash.id());
        assertEquals(1,service.cleanExpiredRecycle());
        assertEquals("ESTIMATED",service.quota(member,"drive").usageState());
        assertEquals(0L,service.reconcileQuota(member,"drive").usedBytes());
    }

    @Test void invalidMoveDoesNotPermanentlyBlockAnUnscannedQuotaAccount() throws Exception {
        enableLedger();var mount=mount();grant(member,mount.id(),"/members/u3","list,read,upload,move",20L);
        assertStatus(404,()->service.move(member,"drive",p("/missing"),p("/target")));
        assertEquals(0,service.quota(member,"drive").pendingWrites());
        service.upload(member,"drive",p("/valid"),bytes("valid"),5);
        assertEquals(5L,service.quota(member,"drive").usedBytes());
    }

    @Test void existingCopyTargetDoesNotRetainAPublicationReservation() throws Exception {
        enableLedger();var mount=mount();grant(member,mount.id(),"/members/u3","list,read,upload,copy",20L);
        service.upload(member,"drive",p("/one"),bytes("one"),3);service.upload(member,"drive",p("/two"),bytes("two"),3);
        assertStatus(409,()->service.copy(member,"drive",p("/one"),p("/two")));
        assertEquals(0,service.quota(member,"drive").pendingWrites());
        assertEquals(0,service.quota(member,"drive").reservedBytes());
        assertEquals(6L,service.reconcileQuota(member,"drive").usedBytes());
        assertEquals("two",Files.readString(mounted.resolve("members/u3/two")));
    }

    @Test void caseVariantDescendantRejectionDoesNotLeaveAnUnknownQuotaIntent() throws Exception {
        enableLedger();var mount=mount();grant(member,mount.id(),"/members/u3","list,read,upload,move,mkdir",20L);
        service.mkdir(member,"drive",p("/folder"));
        int rejected=Path.of("folder").equals(Path.of("FOLDER"))?400:404;
        assertStatus(rejected,()->service.move(member,"drive",p("/folder"),p("/FOLDER/child")));
        assertEquals(0,service.quota(member,"drive").pendingWrites());
        service.upload(member,"drive",p("/valid"),bytes("x"),1);
    }

    private void enableLedger() {
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-quota-schema.sql"),
                new ClassPathResource("db/independent-file-catalog-schema.sql"),new ClassPathResource("db/independent-recycle-schema.sql")).execute(transactions.getDataSource());
        service=new MountService(jdbc,transactions,Clock.systemUTC(),managed,new JdbcAccountDirectory(jdbc),
                new FileCatalog(jdbc,transactions,Clock.systemUTC()),new QuotaLedger(jdbc,transactions,Clock.systemUTC()));
    }
    private void seedAccount(Actor actor) {
        jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,'not-used-by-drive-tests',?,?,TRUE,1,1)",
                actor.id(), actor.accountId(), actor.username(), actor.role().name(), actor.workspaceId());
    }
    private void grant(Actor actor, long mountId, String root, String permissions, Long quota) throws IOException {
        Files.createDirectories(mounted.resolve(DrivePath.of(root).relative()));
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,TRUE,1,1)",
                actor.id(), mountId, root, permissions, quota);
    }
    private static Actor actor(long id, Role role, Long workspace) { return new Actor(id, "SC" + id, "user" + id, null, role, workspace); }
    private static DrivePath p(String value) { return DrivePath.of(value); }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static void assertStatus(int status, Runnable operation) {
        assertEquals(status, assertThrows(ApplicationFault.class, operation::run).httpStatus());
    }
}

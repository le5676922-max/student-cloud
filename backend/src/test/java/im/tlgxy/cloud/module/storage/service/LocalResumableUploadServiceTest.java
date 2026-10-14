package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.request.ResumableUploadCapabilitiesRequest;
import im.tlgxy.cloud.module.storage.model.request.ResumableUploadInitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No business database, real account or cloud directory is used by these tests. */
class LocalResumableUploadServiceTest {
    @TempDir Path work;
    DriverManagerDataSource source;
    DataSourceTransactionManager manager;
    JdbcTemplate jdbc;
    LocalResumableUploadAccess gate;
    DirectoryMetadataCache directories;
    ResumableUploadProperties properties;
    MutableClock clock;
    LocalResumableUploadService service;
    Path cloud, staging;
    AtomicBoolean revoked;
    AtomicReference<String> fingerprint;

    @BeforeEach
    void setup() throws Exception {
        source = new DriverManagerDataSource("jdbc:sqlite:" + work.resolve("resumable.db"));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V71__durable_local_resumable_upload.sql")).execute(source);
        manager = new DataSourceTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        cloud = Files.createDirectory(work.resolve("cloud")).toRealPath();
        staging = work.resolve("staging").toAbsolutePath().normalize();
        properties = new ResumableUploadProperties();
        properties.setStagingRoot(staging.toString());
        clock = new MutableClock();
        directories = mock(DirectoryMetadataCache.class);
        gate = mock(LocalResumableUploadAccess.class);
        revoked = new AtomicBoolean();
        fingerprint = new AtomicReference<>("unit-source-root-fingerprint");
        when(gate.require(anyInt(), anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Authorization must not run under a reservation transaction");
            if (revoked.get()) throw new BizException("permission revoked");
            return access(call.getArgument(1), call.getArgument(2), call.getArgument(3), true);
        });
        service = restarted(jdbc);
    }

    @Test
    void uploadedChunksAndInitReceiptSurviveServiceRestartWithoutOverwriting() throws Exception {
        byte[] bytes = new byte[LocalResumableUploadService.CHUNK_SIZE + 23];
        Arrays.fill(bytes, (byte) 41);
        var request = request("resume.bin", bytes);
        var first = service.initialize(7, request, "private-password");
        assertEquals("OPEN", first.state());
        assertEquals(4_194_304, first.chunkSize());
        assertEquals(clock.millis() + 86_400_000, first.expiresAt());
        receive(first.id(), 0, bytes);
        service = restarted(jdbc);
        var resumed = service.initialize(7, request, "private-password");
        assertEquals(first.id(), resumed.id());
        assertEquals(List.of(0), resumed.receivedChunks());
        assertThrows(BizException.class, () -> service.complete(7, first.id(), "private-password"));
        receive(first.id(), 1, bytes);
        var completed = service.complete(7, first.id(), "private-password");
        assertEquals("COMPLETED", completed.state());
        assertArrayEquals(bytes, Files.readAllBytes(cloud.resolve("resume.bin")));
        assertEquals(0, reserved());
        assertFalse(Files.exists(staging.resolve(first.id())));
        assertEquals(first.id(), restarted(jdbc).initialize(7, request, "private-password").id());
        assertEquals("COMPLETED", restarted(jdbc).complete(7, first.id(), "private-password").state());
        verify(directories, times(2)).invalidate();
        var row = jdbc.queryForMap("SELECT * FROM resumable_upload_session WHERE id=?", first.id());
        assertFalse(row.values().stream().anyMatch(value -> value.toString().contains("private-password")));
    }

    @Test
    void realTemporaryStagingIdentitySupportsUploadAndRestartOnWindowsAndUnix() throws Exception {
        byte[] bytes = {1, 2, 3};
        var upload = service.initialize(7, request("platform-upload.bin", bytes), null);
        Path canonicalStaging = staging.toRealPath();
        String expected = LocalResumableUploadAccess.hash(LocalResumableUploadAccess.directoryFingerprint(canonicalStaging));
        assertEquals(expected, jdbc.queryForObject("SELECT staging_fingerprint FROM resumable_upload_session WHERE id=?", String.class, upload.id()));
        service = restarted(jdbc);
        assertEquals("OPEN", service.status(7, upload.id(), null).state());
        receive(upload.id(), 0, bytes);
        assertEquals("COMPLETED", service.complete(7, upload.id(), null).state());
        assertArrayEquals(bytes, Files.readAllBytes(cloud.resolve("platform-upload.bin")));
    }

    @Test
    void stagingWithMissingFileKeyUsesBirthTimeAndRejectsAChangedOrUnknownIdentity() throws Exception {
        Path canonicalStaging = Files.createDirectory(staging).toRealPath();
        var attributes = mock(BasicFileAttributes.class);
        when(attributes.isDirectory()).thenReturn(true);
        when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("2026-10-10T10:00:00.123456700Z")));
        try (var identity = mockStatic(LocalResumableUploadAccess.class, CALLS_REAL_METHODS)) {
            identity.when(() -> LocalResumableUploadAccess.directoryFingerprint(canonicalStaging))
                    .thenAnswer(ignored -> LocalResumableUploadAccess.directoryFingerprint(canonicalStaging, attributes));
            byte[] bytes = {1, 2, 3};
            var upload = service.initialize(7, request("birth-time-upload.bin", bytes), null);
            receive(upload.id(), 0, bytes);
            service = restarted(jdbc);
            assertEquals(List.of(0), service.status(7, upload.id(), null).receivedChunks());
            when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("2026-10-10T10:00:00.123456800Z")));
            assertThrows(BizException.class, () -> service.status(7, upload.id(), null));
            assertThrows(BizException.class, () -> service.delete(7, upload.id(), null));
            when(attributes.creationTime()).thenReturn(null);
            assertThrows(BizException.class, () -> service.status(7, upload.id(), null));
            when(attributes.creationTime()).thenReturn(FileTime.fromMillis(0));
            assertThrows(BizException.class, () -> service.status(7, upload.id(), null));
            clock.advance(86_400_000);
            service.cleanupExpired();
            assertTrue(Files.exists(canonicalStaging.resolve(upload.id()).resolve("chunk-0.part")));
            assertEquals("OPEN", state(upload.id()));
            assertEquals(6, reserved());
        }
    }

    @Test
    void chunkRetryChecksBothActualBytesAndManifestAndNeverAcceptsAnOversizedStream() throws Exception {
        byte[] bytes = "correct payload".getBytes(StandardCharsets.UTF_8);
        var upload = service.initialize(7, request("data.txt", bytes), null);
        String expected = digest(bytes);
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, expected, new ByteArrayInputStream(new byte[bytes.length - 1]), null));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, expected, new ByteArrayInputStream(new byte[bytes.length + 1]), null));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, expected, new ByteArrayInputStream(new byte[bytes.length]), null));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), -1, expected, new ByteArrayInputStream(bytes), null));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 1, expected, new ByteArrayInputStream(bytes), null));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, "a".repeat(64), new ByteArrayInputStream(bytes), null));
        assertEquals(List.of(), service.status(7, upload.id(), null).receivedChunks());
        receive(upload.id(), 0, bytes);
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, expected, new ByteArrayInputStream(new byte[bytes.length]), null), "An idempotent chunk retry still validates its request body");
        receive(upload.id(), 0, bytes);
        assertEquals(List.of(0), service.status(7, upload.id(), null).receivedChunks());
        assertArrayEquals(bytes, Files.readAllBytes(staging.resolve(upload.id()).resolve("chunk-0.part")));
        assertFalse(Files.exists(staging.resolve(upload.id()).resolve("chunk-0.part.pending")));
    }

    @Test
    void interruptedNetworkDoesNotAcceptPartialChunkAndCanRetry() throws Exception {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        var upload = service.initialize(7, request("data.txt", bytes), null);
        InputStream disconnected = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("local simulated connection loss"); }
        };
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, digest(bytes), disconnected, null));
        assertEquals(List.of(), service.status(7, upload.id(), null).receivedChunks());
        assertFalse(Files.exists(staging.resolve(upload.id()).resolve("chunk-0.part.pending")));
        receive(upload.id(), 0, bytes);
        assertEquals("COMPLETED", service.complete(7, upload.id(), null).state());
    }

    @Test
    void reselectingSameFileReconcilesMissingPartsBeforeClientSkipsThem() throws Exception {
        byte[] bytes = {1, 2, 3}; var request = request("data.bin", bytes);
        var upload = service.initialize(7, request, null); receive(upload.id(), 0, bytes);
        Files.delete(staging.resolve(upload.id()).resolve("chunk-0.part"));
        var resumed = restarted(jdbc).initialize(7, request, null);
        assertEquals(upload.id(), resumed.id()); assertEquals(List.of(), resumed.receivedChunks());
        receive(upload.id(), 0, bytes);
        assertEquals("COMPLETED", service.complete(7, upload.id(), null).state());
    }

    @Test
    void concurrentInitOfSameManifestReservesCapacityOnlyOnce() throws Exception {
        var request = request("data.bin", new byte[]{1}); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.initialize(7, request, null); });
            var second = executor.submit(() -> { start.await(); return service.initialize(7, request, null); });
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS).id(), second.get(15, TimeUnit.SECONDS).id());
        }
        assertEquals(2, reserved());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM resumable_upload_session", Integer.class));
    }

    @Test
    void completedChunksAreRehashedAndMissingDiskPartsBecomeResendable() throws Exception {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        var upload = service.initialize(7, request("data.txt", bytes), null);
        receive(upload.id(), 0, bytes);
        Path part = staging.resolve(upload.id()).resolve("chunk-0.part");
        Files.write(part, "changed".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class, () -> service.complete(7, upload.id(), null));
        assertFalse(Files.exists(cloud.resolve("data.txt")));
        assertEquals("OPEN", state(upload.id()));
        Files.delete(part);
        assertEquals(List.of(), restarted(jdbc).status(7, upload.id(), null).receivedChunks());
        receive(upload.id(), 0, bytes);
        assertEquals("COMPLETED", service.complete(7, upload.id(), null).state());
    }

    @Test
    void ownershipAndFreshGrantAreRequiredOnEveryRequest() throws Exception {
        byte[] bytes = {1, 2, 3};
        var upload = service.initialize(7, request("data.bin", bytes), "secret");
        assertThrows(BizException.class, () -> service.status(8, upload.id(), "secret"));
        assertThrows(BizException.class, () -> service.receiveChunk(8, upload.id(), 0, digest(bytes), new ByteArrayInputStream(bytes), "secret"));
        assertThrows(BizException.class, () -> service.complete(8, upload.id(), "secret"));
        assertThrows(BizException.class, () -> service.delete(8, upload.id(), "secret"));
        assertThrows(BizException.class, () -> service.status(7, "../../elsewhere", null));
        revoked.set(true);
        assertThrows(BizException.class, () -> service.initialize(7, request("data.bin", bytes), "secret"));
        assertThrows(BizException.class, () -> service.status(7, upload.id(), "secret"));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, digest(bytes), new ByteArrayInputStream(bytes), "secret"));
        assertThrows(BizException.class, () -> service.complete(7, upload.id(), "secret"));
        assertThrows(BizException.class, () -> service.delete(7, upload.id(), "secret"));
        assertEquals("OPEN", state(upload.id()));
        assertFalse(Files.exists(cloud.resolve("data.bin")));
    }

    @Test
    void revokeWhileReceivingDoesNotAcceptBytesOrKeepAnUnverifiedPendingPart() throws Exception {
        byte[] bytes = {1, 2, 3};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        var stream = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int count) {
                int read = super.read(target, offset, count); revoked.set(true); return read;
            }
        };
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, digest(bytes), stream, null));
        assertEquals("", jdbc.queryForObject("SELECT received_mask FROM resumable_upload_session WHERE id=?", String.class, upload.id()));
        assertFalse(Files.exists(staging.resolve(upload.id()).resolve("chunk-0.part")));
        assertFalse(Files.exists(staging.resolve(upload.id()).resolve("chunk-0.part.pending")));
    }

    @Test
    void sourceRootReplacementOrGrantBindingChangeCannotResumeOldBytes() throws Exception {
        byte[] bytes = {1};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        fingerprint.set("different-source-fingerprint");
        assertThrows(BizException.class, () -> service.status(7, upload.id(), null));
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, digest(bytes), new ByteArrayInputStream(bytes), null));
        assertThrows(BizException.class, () -> service.complete(7, upload.id(), null));
        assertThrows(BizException.class, () -> service.delete(7, upload.id(), null));
        assertNotEquals(upload.id(), service.initialize(7, request("data.bin", bytes), null).id());
    }

    @Test
    void targetCollisionNeverOverwritesAndDoesNotMarkPublicationAttempted() throws Exception {
        byte[] bytes = {1};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        receive(upload.id(), 0, bytes);
        Files.write(cloud.resolve("data.bin"), new byte[]{9});
        assertThrows(BizException.class, () -> service.complete(7, upload.id(), null));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(cloud.resolve("data.bin")));
        assertEquals("OPEN", state(upload.id()));
        assertThrows(BizException.class, () -> service.initialize(7, request("data.bin", new byte[]{2}), null));
        service.delete(7, upload.id(), null);
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(cloud.resolve("data.bin")));
        assertEquals(0, reserved());
    }

    @Test
    void completedFileIsNeverReplayedWhenReceiptCommitFails() throws Exception {
        byte[] bytes = {1, 2, 3};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        receive(upload.id(), 0, bytes);
        JdbcTemplate failing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("UPDATE resumable_upload_session SET state='COMPLETED'"))
                    throw new DataAccessResourceFailureException("simulated durable receipt failure");
                return super.update(sql, args);
            }
        };
        assertThrows(BizException.class, () -> restarted(failing).complete(7, upload.id(), null));
        assertEquals("COMPLETING", state(upload.id()));
        assertArrayEquals(bytes, Files.readAllBytes(cloud.resolve("data.bin")));
        Files.delete(cloud.resolve("data.bin"));
        assertEquals("COMPLETING", restarted(jdbc).complete(7, upload.id(), null).state());
        assertEquals("COMPLETING", restarted(jdbc).initialize(7, request("data.bin", bytes), null).state());
        assertFalse(Files.exists(cloud.resolve("data.bin")), "Uncertain completion must not silently recreate the destination");
    }

    @Test
    void completingMarkerIsCommittedBeforePublicationAndFailureDoesNotReplay() throws Exception {
        byte[] bytes = {1};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        receive(upload.id(), 0, bytes);
        doAnswer(call -> {
            assertEquals("OPEN", state(upload.id()));
            doAnswer(auth -> {
                if ("COMPLETING".equals(state(upload.id()))) throw new BizException("revoked just before publication");
                return access(auth.getArgument(1), auth.getArgument(2), auth.getArgument(3), true);
            }).when(gate).require(anyInt(), anyString(), anyString(), anyString(), nullable(String.class));
            return null;
        }).when(directories).invalidate();
        assertThrows(BizException.class, () -> service.complete(7, upload.id(), null));
        assertEquals("COMPLETING", state(upload.id()));
        assertFalse(Files.exists(cloud.resolve("data.bin")));
        reset(gate);
        when(gate.require(anyInt(), anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(auth -> access(auth.getArgument(1), auth.getArgument(2), auth.getArgument(3), true));
        assertEquals("COMPLETING", restarted(jdbc).complete(7, upload.id(), null).state());
        assertFalse(Files.exists(cloud.resolve("data.bin")));
    }

    @Test
    void expiresAfter24HoursAndCleanupOnlyTouchesOwnedKnownTemporaryPaths() throws Exception {
        byte[] bytes = {1, 2, 3};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        receive(upload.id(), 0, bytes);
        Files.write(cloud.resolve("keep.bin"), new byte[]{8});
        Files.write(staging.resolve("unrelated.txt"), new byte[]{9});
        clock.advance(86_400_000);
        assertEquals("EXPIRED", service.status(7, upload.id(), null).state());
        assertThrows(BizException.class, () -> service.receiveChunk(7, upload.id(), 0, digest(bytes), new ByteArrayInputStream(bytes), null));
        assertThrows(BizException.class, () -> service.complete(7, upload.id(), null));
        service.cleanupExpired();
        assertEquals(0, reserved());
        assertEquals("DELETED", state(upload.id()));
        assertFalse(Files.exists(staging.resolve(upload.id())));
        assertTrue(Files.exists(cloud.resolve("keep.bin")));
        assertTrue(Files.exists(staging.resolve("unrelated.txt")));
        assertThrows(BizException.class, () -> service.status(7, upload.id(), null));
    }

    @Test
    void unexpectedTemporaryContentBlocksUnsafeCleanupAndKeepsQuotaReservation() throws Exception {
        var upload = service.initialize(7, request("data.bin", new byte[]{1}), null);
        Path unknown = Files.write(staging.resolve(upload.id()).resolve("not-a-owned-part.txt"), new byte[]{9});
        clock.advance(86_400_000);
        service.cleanupExpired();
        assertTrue(Files.exists(unknown));
        assertEquals("OPEN", state(upload.id()));
        assertEquals(2, reserved());
    }

    @Test
    void failedExpiredRowsDoNotStarveLaterReceiptsOrReleaseTheirReservations() throws Exception {
        for (int index = 0; index < 33; index++) service.initialize(100 + index, request("expired-" + index, new byte[]{1}), null);
        List<String> ids = jdbc.queryForList("SELECT id FROM resumable_upload_session ORDER BY expires_at,id", String.class);
        for (String id : ids.subList(0, 32))
            jdbc.update("UPDATE resumable_upload_session SET staging_fingerprint=? WHERE id=?", "invalid-root-fingerprint", id);
        clock.advance(86_400_000);
        service.cleanupExpired();
        assertEquals(66, reserved());
        assertEquals("OPEN", state(ids.getLast()));
        service.cleanupExpired();
        assertEquals(64, reserved());
        assertEquals("DELETED", state(ids.getLast()));
        assertFalse(Files.exists(staging.resolve(ids.getLast())));
        for (String failed : ids.subList(0, 32)) {
            assertEquals("OPEN", state(failed)); assertTrue(Files.exists(staging.resolve(failed)));
        }
        service.cleanupExpired(); // End of keyset scan: wrap, without unbounded extra queries/deletion.
        assertEquals(64, reserved());
    }

    @Test
    void durableCapacityReservationBoundsActorAndGlobalStaging() throws Exception {
        for (int index = 0; index < 8; index++) service.initialize(7, request("file-" + index, new byte[]{1}), null);
        assertThrows(BizException.class, () -> service.initialize(7, request("file-9", new byte[]{1}), null));
        assertEquals(16, reserved());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM resumable_upload_session", Integer.class));
        service.initialize(8, request("another-actor", new byte[]{1}), null);
        properties.setQuotaBytes(8L * 1024 * 1024);
        List<String> hashes = List.of("a".repeat(64), "b".repeat(64));
        var tooLarge = new ResumableUploadInitRequest("local", "/", "large.bin", 6L * 1024 * 1024, LocalResumableUploadAccess.hash(String.join("\n", hashes)), hashes);
        assertThrows(BizException.class, () -> restarted(jdbc).initialize(9, tooLarge, null));
        assertEquals(18, reserved());
    }

    @Test
    void invalidManifestOversizedFileAndNonlocalDriverNeverCreateASession() throws Exception {
        var good = request("data.bin", new byte[]{1});
        assertThrows(BizException.class, () -> service.initialize(7, new ResumableUploadInitRequest("local", "/", "data.bin", 1, "0".repeat(64), good.chunkHashes()), null));
        assertThrows(BizException.class, () -> service.initialize(7, new ResumableUploadInitRequest("local", "/", "data.bin", 4_194_305, good.manifestHash(), good.chunkHashes()), null));
        assertThrows(BizException.class, () -> service.initialize(7, new ResumableUploadInitRequest("local", "/", "data.bin", 2_147_483_649L, good.manifestHash(), good.chunkHashes()), null));
        doAnswer(auth -> access(auth.getArgument(1), auth.getArgument(2), auth.getArgument(3), false))
                .when(gate).require(anyInt(), anyString(), anyString(), anyString(), nullable(String.class));
        assertFalse(service.capabilities(7, new ResumableUploadCapabilitiesRequest("remote", "/", "data.bin", 8_388_608), null).enabled());
        assertThrows(BizException.class, () -> service.initialize(7, good, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resumable_upload_session", Integer.class));
        assertFalse(Files.exists(staging));
    }

    @Test
    void stagingCannotBeInsideOrContainTheCloudRootAndChangedVolumeCannotEraseOldParts() throws Exception {
        properties.setStagingRoot(cloud.resolve("private-staging").toString());
        assertThrows(BizException.class, () -> service.initialize(7, request("data.bin", new byte[]{1}), null));
        assertFalse(Files.exists(cloud.resolve("private-staging")));
        properties.setStagingRoot(work.toString());
        assertThrows(BizException.class, () -> service.initialize(7, request("data.bin", new byte[]{1}), null));
        properties.setStagingRoot(staging.toString());
        var upload = service.initialize(7, request("data.bin", new byte[]{1}), null);
        receive(upload.id(), 0, new byte[]{1});
        Path oldPart = staging.resolve(upload.id()).resolve("chunk-0.part");
        properties.setStagingRoot(work.resolve("replacement-staging").toString());
        assertThrows(BizException.class, () -> service.status(7, upload.id(), null));
        assertThrows(BizException.class, () -> service.delete(7, upload.id(), null));
        clock.advance(86_400_000);
        service.cleanupExpired();
        assertTrue(Files.exists(oldPart));
        assertEquals(2, reserved());
    }

    @Test
    void deletingACompletedReceiptNeverDeletesThePublishedFile() throws Exception {
        byte[] bytes = {1, 2, 3};
        var upload = service.initialize(7, request("data.bin", bytes), null);
        receive(upload.id(), 0, bytes);
        service.complete(7, upload.id(), null);
        service.delete(7, upload.id(), null);
        assertArrayEquals(bytes, Files.readAllBytes(cloud.resolve("data.bin")));
        assertEquals("DELETED", state(upload.id()));
        assertEquals(0, reserved());
    }

    private LocalResumableUploadService restarted(JdbcTemplate template) {
        return new LocalResumableUploadService(template, manager, gate, directories, properties, clock);
    }
    private LocalResumableUploadAccess.Access access(String key, String path, String name, boolean local) {
        return new LocalResumableUploadAccess.Access(9, key, "/", fingerprint.get(), path, name,
                cloud, cloud, cloud.resolve(path.substring(1)).resolve(name), local, true);
    }
    private long reserved() { return jdbc.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM resumable_upload_session", Long.class); }
    private String state(String id) { return jdbc.queryForObject("SELECT state FROM resumable_upload_session WHERE id=?", String.class, id); }
    private void receive(String id, int index, byte[] all) throws Exception {
        int start = index * LocalResumableUploadService.CHUNK_SIZE;
        byte[] part = Arrays.copyOfRange(all, start, Math.min(all.length, start + LocalResumableUploadService.CHUNK_SIZE));
        service.receiveChunk(7, id, index, digest(part), new ByteArrayInputStream(part), null);
    }
    private static ResumableUploadInitRequest request(String name, byte[] bytes) throws Exception {
        List<String> hashes = new ArrayList<>();
        for (int start = 0; start < bytes.length; start += LocalResumableUploadService.CHUNK_SIZE)
            hashes.add(digest(Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + LocalResumableUploadService.CHUNK_SIZE))));
        return new ResumableUploadInitRequest("local", "/", name, bytes.length, LocalResumableUploadAccess.hash(String.join("\n", hashes)), hashes);
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(Instant.parse("2026-10-10T10:00:00Z").toEpochMilli());
        void advance(long milliseconds) { now.addAndGet(milliseconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    }
}

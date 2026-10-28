package io.studentcloud.storage;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.ObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real SDK requests against a loopback protocol fixture; all credentials and objects are synthetic. */
class SdkObjectStoreProtocolTest {
    @Test void zeroByteDirectoryMarkerFromMemoryUsesRealS3Sdk() throws Exception {
        try (Fixture fixture = new Fixture(ObjectStore.Provider.S3); ObjectStore store = fixture.open()) {
            var receipt = store.createOnly("space/folder/", new ByteArrayInputStream(new byte[0]), 0, "zero-memory");
            assertEquals(0, receipt.size());
            assertTrue(store.head("space/folder/").isPresent());
            assertEquals(0, fixture.objects.get("space/folder/").bytes.length);
            assertZeroLengthRequest(fixture);
        }
    }

    @Test void zeroByteDirectoryMarkerFromStagedFileUsesRealS3Sdk(@TempDir Path directory) throws Exception {
        Path staged = Files.createFile(directory.resolve("empty-upload"));
        try (Fixture fixture = new Fixture(ObjectStore.Provider.S3); ObjectStore store = fixture.open();
             InputStream input = Files.newInputStream(staged)) {
            var receipt = store.createOnly("space/folder/", input, 0, "zero-staged-file");
            assertEquals(0, receipt.size());
            assertTrue(store.head("space/folder/").isPresent());
            assertEquals(0, fixture.objects.get("space/folder/").bytes.length);
            assertZeroLengthRequest(fixture);
        }
    }

    @Test void s3UploadFromStagedFileRemainsRepeatableBeyondSigningReadLimit(@TempDir Path directory) throws Exception {
        byte[] content = new byte[512 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 251);
        Path staged = Files.write(directory.resolve("large-upload"), content);
        try (Fixture fixture = new Fixture(ObjectStore.Provider.S3); ObjectStore store = fixture.open();
             InputStream input = Files.newInputStream(staged)) {
            var receipt = store.createOnly("space/large-upload.bin", input, content.length, "large-staged-file");
            assertEquals(content.length, receipt.size());
            assertArrayEquals(content, fixture.objects.get("space/large-upload.bin").bytes);
            assertEquals(1, fixture.puts.get());
        }
    }

    @Test void s3RejectsActualLengthMismatchBeforeAnyProviderWrite() throws Exception {
        try (Fixture fixture = new Fixture(ObjectStore.Provider.S3); ObjectStore store = fixture.open()) {
            for (long declared : new long[]{2, 6}) {
                var failure = assertThrows(ObjectStore.WriteFailure.class,
                        () -> store.createOnly("space/length.bin", input("four"), declared, "mismatch"));
                assertFalse(failure.mayHavePublished());
            }
            assertEquals(0, fixture.puts.get());
        }
    }

    @Test void s3UploadLeavesCallerInputOpenAndDeletesAdapterOwnedStaging() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream callerOwned = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() { closed.set(true); }
        };
        try (Fixture fixture = new Fixture(ObjectStore.Provider.S3); ObjectStore store = fixture.open()) {
            store.createOnly("space/caller-owned", callerOwned, 3, "caller-owned-stream");
            assertFalse(closed.get(), "the upload caller retains stream ownership");
        } finally { callerOwned.close(); }
        Path ownedFile;
        try (RepeatableS3Body body = RepeatableS3Body.stage(input("repeatable"), 10)) {
            ownedFile = body.file();
            assertArrayEquals("repeatable".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(ownedFile));
        }
        assertFalse(Files.exists(ownedFile));
    }

    @Test void s3StagingBoundsInputThatReturnsNoProgress() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        InputStream stalled = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] bytes, int offset, int length) { reads.incrementAndGet(); return 0; }
        };
        try (Fixture fixture = new Fixture(ObjectStore.Provider.S3); ObjectStore store = fixture.open()) {
            ObjectStore.WriteFailure failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertThrows(ObjectStore.WriteFailure.class, () -> store.createOnly("space/stalled", stalled, 1, "stalled-stream")));
            assertEquals(400, failure.httpStatus());
            assertFalse(failure.mayHavePublished());
            assertTrue(reads.get() <= 1025);
            assertEquals(0, fixture.puts.get());
        }
    }

    private static void assertZeroLengthRequest(Fixture fixture) {
        Request put = fixture.requests.stream().filter(request -> request.method.equals("PUT")).findFirst().orElseThrow();
        assertEquals("0", put.header("Content-Length"));
        assertNull(put.header("Transfer-Encoding"));
        assertEquals("*", put.header("If-None-Match"));
        assertEquals(1, fixture.puts.get());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", put.header("x-amz-content-sha256"));
        assertNull(put.header("x-amz-sdk-checksum-algorithm"));
        assertNull(put.header("x-amz-checksum-crc32"));
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void conditionalCreateDoesNotOverwriteAndReturnsAConfirmedConflict(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            byte[] original = "original-content".getBytes(StandardCharsets.UTF_8);
            var receipt = store.createOnly("space/one.txt", input(original), original.length, "first-operation");
            assertEquals(original.length, receipt.size());
            assertFalse(receipt.etag().isBlank());
            assertEquals("first-operation", store.head("space/one.txt").orElseThrow().operationId());
            ObjectStore.WriteFailure conflict = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/one.txt", input("replacement"), 11, "second-operation"));
            assertEquals(409, conflict.httpStatus());
            assertFalse(conflict.mayHavePublished());
            assertNull(conflict.getCause());
            try (var read = store.read("space/one.txt")) {
                assertArrayEquals(original, read.input().readAllBytes());
                assertEquals(original.length, read.item().size());
                assertEquals("first-operation", read.item().operationId());
            }
            List<Request> writes = fixture.requests.stream().filter(r -> r.method.equals("PUT")).toList();
            assertEquals(2, writes.size(), "one attempt for each explicit create");
            for (Request write : writes) {
                assertEquals(provider == ObjectStore.Provider.S3 ? "*" : "true",
                        write.header(provider == ObjectStore.Provider.S3 ? "If-None-Match" : "x-oss-forbid-overwrite"));
                assertNotNull(write.header("Authorization"));
                assertTrue(write.header("Authorization").startsWith(provider == ObjectStore.Provider.S3 ? "AWS4-HMAC-SHA256" : "OSS4-HMAC-SHA256"));
                assertNotNull(write.header(provider == ObjectStore.Provider.S3 ? "x-amz-security-token" : "x-oss-security-token"));
            }
            assertEquals("first-operation", writes.getFirst().header(fixture.metadataPrefix + "studentcloud-operation-id"));
            assertNull(writes.getFirst().header(fixture.metadataPrefix + "studentcloud-probe"));
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void listingUsesOneBoundedProviderPageAndPassesContinuationToken(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            ObjectStore.Page page = store.list("space/", "/", "opaque+cursor=", 2);
            assertEquals(1, page.objects().size());
            assertEquals("space/notes.txt", page.objects().getFirst().key());
            assertEquals(7, page.objects().getFirst().size());
            assertNull(page.objects().getFirst().operationId(), "list results do not imply metadata ownership");
            assertEquals(List.of("space/folder/"), page.prefixes());
            assertEquals("next+cursor=", page.nextToken());
            assertEquals(1, fixture.requests.size(), "list must not iterate the full provider paginator");
            Request request = fixture.requests.getFirst();
            assertEquals("space/", request.query.get("prefix"));
            assertEquals("/", request.query.get("delimiter"));
            assertEquals("2", request.query.get("max-keys"));
            assertEquals("opaque+cursor=", request.query.get("continuation-token"));
            assertThrows(ApplicationFault.class, () -> store.list("space/", "/", null, 1001));
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test void nativeOssFetchesCompleteVersioningAndRejectsEnabledAndSuspendedBeforePut() throws Exception {
        try (Fixture fixture = new Fixture(ObjectStore.Provider.ALIYUN_OSS); ObjectStore store = fixture.open()) {
            for (String status : List.of("Enabled", "Suspended")) {
                fixture.versioning = status;
                var failure = assertThrows(ObjectStore.WriteFailure.class,
                        () -> store.createOnly("space/blocked.txt", input("data"), 4, "write-operation"));
                assertFalse(failure.mayHavePublished());
                assertEquals(409, failure.httpStatus());
            }
            assertEquals(2, fixture.requests.size());
            assertTrue(fixture.requests.stream().allMatch(r -> r.method.equals("GET") && r.query.containsKey("versioning")));
            assertEquals(0, fixture.puts.get());
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void probeCleanupChecksDurableOwnershipAndOnlyDeletesTheReceipt(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            String nonce = UUID.randomUUID().toString();
            String key = "private/workspace-7/.studentcloud-probes/" + nonce;
            ObjectStore.Receipt receipt;
            try (ObjectStore store = fixture.open()) { receipt = store.createOnly(key, input("probe"), 5, nonce); }
            try (ObjectStore reopened = fixture.open()) {
                reopened.cleanupProbe(receipt);
                assertFalse(fixture.objects.containsKey(key));
                assertEquals(1, fixture.deletes.get());
                Request delete = fixture.requests.stream().filter(r -> r.method.equals("DELETE")).findFirst().orElseThrow();
                assertEquals("/test-bucket/" + key, delete.path);
                assertNotNull(delete.header("If-Match"));
                var userObject = new ObjectStore.Receipt("private/workspace-7/notes.txt", 5, receipt.etag(), null);
                assertThrows(ApplicationFault.class, () -> reopened.cleanupProbe(userObject));
                assertEquals(1, fixture.deletes.get());
            }
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void cleanupRefusesReplacedContentWrongOwnerAndMismatchedVersion(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            String nonce = UUID.randomUUID().toString();
            String key = "space/.studentcloud-probes/" + nonce;
            var receipt = store.createOnly(key, input("probe"), 5, nonce);
            Stored original = fixture.objects.get(key);
            fixture.objects.put(key, new Stored("other".getBytes(StandardCharsets.UTF_8), "different-etag", original.metadata, null));
            assertThrows(ApplicationFault.class, () -> store.cleanupProbe(receipt));
            fixture.objects.put(key, new Stored(original.bytes, original.etag,
                    Map.of("studentcloud-operation-id", "wrong-owner", "studentcloud-probe", "true"), null));
            assertThrows(ApplicationFault.class, () -> store.cleanupProbe(receipt));
            fixture.objects.put(key, new Stored(original.bytes, original.etag, original.metadata, "new-version"));
            assertThrows(ApplicationFault.class, () -> store.cleanupProbe(receipt));
            assertEquals(0, fixture.deletes.get());
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void cleanupUsesExactVersionAndReportsRemoteFailure(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            String nonce = UUID.randomUUID().toString();
            String key = "space/.studentcloud-probes/" + nonce;
            fixture.objects.put(key, new Stored("probe".getBytes(StandardCharsets.UTF_8), "owned-etag",
                    Map.of("studentcloud-operation-id", nonce, "studentcloud-probe", "true"), "version-owned"));
            var receipt = new ObjectStore.Receipt(key, 5, "owned-etag", "version-owned");
            fixture.versioning = "Enabled";
            fixture.deleteStatus = 503;
            ApplicationFault failure = assertThrows(ApplicationFault.class, () -> store.cleanupProbe(receipt));
            assertEquals("OBJECT_STORE_PROBE_CLEANUP", failure.code());
            assertTrue(fixture.objects.containsKey(key));
            fixture.deleteStatus = 204;
            store.cleanupProbe(receipt);
            List<Request> deletes = fixture.requests.stream().filter(r -> r.method.equals("DELETE")).toList();
            assertEquals(2, deletes.size(), "cleanup failures must not trigger automatic retries");
            assertTrue(deletes.stream().allMatch(r -> "version-owned".equals(r.query.get("versionId"))));
            assertFalse(fixture.objects.containsKey(key));
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void headOnlyTreatsMissingAsEmptyAndDoesNotHideAuthorizationFailure(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            assertTrue(store.head("space/missing").isEmpty());
            fixture.headStatus = 403;
            ApplicationFault denied = assertThrows(ApplicationFault.class, () -> store.head("space/forbidden"));
            assertEquals(403, denied.httpStatus());
            assertFalse(denied.getMessage().contains("provider-secret"));
            assertNull(denied.getCause());
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void serverWriteFailureIsUncertainAndNeverRetried(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            fixture.putStatus = 503;
            var failure = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/upload", input("data"), 4, "operation"));
            assertEquals(503, failure.httpStatus());
            assertTrue(failure.mayHavePublished());
            assertEquals(1, fixture.puts.get());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("provider-secret"));
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void providerAuthorizationRejectIsDefiniteAndInputValidationMakesNoRequest(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            fixture.putStatus = 403;
            var denied = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/upload", input("data"), 4, "operation"));
            assertEquals(403, denied.httpStatus());
            assertFalse(denied.mayHavePublished());
            int count = fixture.requests.size();
            var invalid = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/../bad", input("data"), 4, "operation"));
            assertFalse(invalid.mayHavePublished());
            var tooLarge = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/large", input("data"), 52_428_801, "operation"));
            assertEquals(413, tooLarge.httpStatus());
            assertFalse(tooLarge.mayHavePublished());
            assertEquals(count, fixture.requests.size());
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void timeoutAfterPutIsUncertainAndHasNoAutomaticReplay(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider);
             ObjectStore store = fixture.open(new SdkObjectStoreFactory(Duration.ofMillis(100), Duration.ofMillis(250), 1))) {
            fixture.putDelayMillis = 1000;
            var failure = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/slow", input("data"), 4, "operation"));
            assertTrue(failure.mayHavePublished());
            assertEquals(504, failure.httpStatus());
            assertEquals(1, fixture.puts.get());
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void redirectsAreNotFollowedAndWriteResultIsUncertain(ObjectStore.Provider provider) throws Exception {
        try (Fixture destination = new Fixture(provider); Fixture fixture = new Fixture(provider); ObjectStore store = fixture.open()) {
            fixture.redirect = destination.endpoint().toString() + "/unexpected";
            var failure = assertThrows(ObjectStore.WriteFailure.class,
                    () -> store.createOnly("space/redirect", input("data"), 4, "operation"));
            assertTrue(failure.mayHavePublished());
            assertEquals(0, destination.requests.size(), "signed headers must never reach a redirect target");
            assertEquals(1, fixture.puts.get());
        }
    }

    @ParameterizedTest @EnumSource(ObjectStore.Provider.class)
    void closingPartialReadReleasesConnectionAndStoreCloseClosesOpenReads(ObjectStore.Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            byte[] large = new byte[64 * 1024];
            Arrays.fill(large, (byte) 7);
            fixture.objects.put("space/large", new Stored(large, "large-etag", Map.of(), null));
            ObjectStore store = fixture.open(new SdkObjectStoreFactory(Duration.ofSeconds(1), Duration.ofSeconds(2), 1));
            try (var read = store.read("space/large")) { assertEquals(7, read.input().read()); }
            assertTrue(store.head("space/large").isPresent(), "a closed partial stream must not exhaust the connection pool");
            ObjectStore.Read open = store.read("space/large");
            store.close();
            store.close();
            assertThrows(IOException.class, () -> open.input().read());
            assertThrows(ApplicationFault.class, () -> store.head("space/large"));
        }
    }

    @Test void unsafeEndpointComponentsAreRejectedBeforeOpeningAnySdk() {
        var factory = new SdkObjectStoreFactory();
        for (String endpoint : List.of("https://user:password@example.com", "https://example.com/path", "https://example.com?secret=value", "https://example.com#fragment")) {
            ApplicationFault fault = assertThrows(ApplicationFault.class, () -> factory.open(new ObjectStore.Connection(
                    ObjectStore.Provider.S3, URI.create(endpoint), "test-bucket", "us-east-1", true, "synthetic-access", "synthetic-secret", null)));
            assertEquals(400, fault.httpStatus());
            assertFalse(fault.getMessage().contains("password"));
        }
    }

    private static ByteArrayInputStream input(String value) { return input(value.getBytes(StandardCharsets.UTF_8)); }
    private static ByteArrayInputStream input(byte[] value) { return new ByteArrayInputStream(value); }

    private record Request(String method, String path, Map<String, String> query, Map<String, List<String>> headers) {
        String header(String name) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().getFirst()).findFirst().orElse(null); }
    }
    private record Stored(byte[] bytes, String etag, Map<String, String> metadata, String version) {}

    private static final class Fixture implements AutoCloseable {
        final ObjectStore.Provider provider;
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final Map<String, Stored> objects = new ConcurrentHashMap<>();
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final String metadataPrefix;
        volatile String versioning = "";
        volatile int putStatus = 200;
        volatile int deleteStatus = 204;
        volatile int headStatus;
        volatile long putDelayMillis;
        volatile String redirect;

        Fixture(ObjectStore.Provider provider) throws IOException {
            this.provider = provider;
            metadataPrefix = provider == ObjectStore.Provider.S3 ? "x-amz-meta-" : "x-oss-meta-";
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        ObjectStore open() { return open(new SdkObjectStoreFactory()); }
        ObjectStore open(SdkObjectStoreFactory factory) {
            return factory.open(new ObjectStore.Connection(provider, endpoint(), "test-bucket", "us-east-1", true,
                    "synthetic-access", "synthetic-secret", "synthetic-session-token"));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, value) -> headers.put(key, List.copyOf(value)));
            requests.add(new Request(method, path, query, headers));
            try {
                if (query.containsKey("versioning")) {
                    send(exchange, 200, "<VersioningConfiguration" + xmlNamespace() + ">"
                            + (versioning.isEmpty() ? "" : "<Status>" + versioning + "</Status>") + "</VersioningConfiguration>");
                    return;
                }
                if (query.containsKey("list-type")) {
                    send(exchange, 200, "<ListBucketResult" + xmlNamespace() + "><Name>test-bucket</Name><Prefix>space/</Prefix>"
                            + "<KeyCount>2</KeyCount><MaxKeys>2</MaxKeys><Delimiter>/</Delimiter><IsTruncated>true</IsTruncated><NextContinuationToken>next+cursor=</NextContinuationToken>"
                            + "<Contents><Key>space/notes.txt</Key><LastModified>2026-10-27T00:00:00.000Z</LastModified><ETag>&quot;notes-etag&quot;</ETag><Size>7</Size><StorageClass>STANDARD</StorageClass></Contents>"
                            + "<CommonPrefixes><Prefix>space/folder/</Prefix></CommonPrefixes></ListBucketResult>");
                    return;
                }
                String key = path.startsWith("/test-bucket/") ? path.substring("/test-bucket/".length()) : path.substring(1);
                if (method.equals("PUT")) {
                    puts.incrementAndGet();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if (putDelayMillis > 0) try { Thread.sleep(putDelayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    if (redirect != null) { exchange.getResponseHeaders().set("Location", redirect); error(exchange, 307, "TemporaryRedirect"); return; }
                    if (putStatus != 200) { error(exchange, putStatus, putStatus == 403 ? "AccessDenied" : "ServiceUnavailable"); return; }
                    if (objects.containsKey(key)) { error(exchange, provider == ObjectStore.Provider.S3 ? 412 : 409, provider == ObjectStore.Provider.S3 ? "PreconditionFailed" : "FileAlreadyExists"); return; }
                    Map<String, String> metadata = new LinkedHashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) -> { if (name.toLowerCase().startsWith(metadataPrefix)) metadata.put(name.substring(metadataPrefix.length()).toLowerCase(), values.getFirst()); });
                    String etag;
                    try { etag = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body)); } catch (Exception failure) { throw new IOException(failure); }
                    objects.put(key, new Stored(body, etag, Map.copyOf(metadata), null));
                    exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
                    send(exchange, 200, "");
                    return;
                }
                Stored object = objects.get(key);
                if (method.equals("HEAD") && headStatus != 0) { error(exchange, headStatus, "AccessDenied"); return; }
                if (object == null) { error(exchange, 404, "NoSuchKey"); return; }
                metadata(exchange.getResponseHeaders(), object);
                if (method.equals("HEAD")) { exchange.getResponseHeaders().set("Content-Length", Integer.toString(object.bytes.length)); exchange.sendResponseHeaders(200, -1); return; }
                if (method.equals("GET")) { exchange.sendResponseHeaders(200, object.bytes.length); exchange.getResponseBody().write(object.bytes); return; }
                if (method.equals("DELETE")) {
                    deletes.incrementAndGet();
                    if (deleteStatus != 204) { error(exchange, deleteStatus, "ServiceUnavailable"); return; }
                    objects.remove(key); exchange.sendResponseHeaders(204, -1); return;
                }
                error(exchange, 400, "InvalidRequest");
            } finally { exchange.close(); }
        }

        private void metadata(Headers headers, Stored object) {
            headers.set("ETag", "\"" + object.etag + "\"");
            headers.set("Last-Modified", "Tue, 27 Oct 2026 00:00:00 GMT");
            headers.set("Content-Type", "application/octet-stream");
            object.metadata.forEach((key, value) -> headers.set(metadataPrefix + key, value));
            if (object.version != null) headers.set(provider == ObjectStore.Provider.S3 ? "x-amz-version-id" : "x-oss-version-id", object.version);
        }
        private String xmlNamespace() { return provider == ObjectStore.Provider.S3 ? " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"" : ""; }
        private void error(HttpExchange exchange, int status, String code) throws IOException {
            exchange.getResponseHeaders().set("x-oss-error-code", code);
            if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(status, -1); return; }
            send(exchange, status, "<Error><Code>" + code + "</Code><Message>provider-secret endpoint/key details</Message><RequestId>synthetic-request</RequestId><HostId>synthetic-host</HostId></Error>");
        }
        private void send(HttpExchange exchange, int status, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        }
        private static Map<String, String> query(String raw) {
            Map<String, String> result = new LinkedHashMap<>();
            if (raw != null) for (String part : raw.split("&")) {
                String[] pair = part.split("=", 2);
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            return result;
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}

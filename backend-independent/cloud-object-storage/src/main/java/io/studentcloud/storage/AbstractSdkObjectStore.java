package io.studentcloud.storage;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.ObjectStore;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractSdkObjectStore implements ObjectStore {
    static final String OPERATION_METADATA = "studentcloud-operation-id";
    static final String PROBE_METADATA = "studentcloud-probe";
    static final long MAX_OBJECT_SIZE = 50L * 1024 * 1024;
    private static final Pattern PROBE = Pattern.compile("^(?:[^/]+/)*\\.studentcloud-probes/([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$");
    final String bucket;
    private final Set<InputStream> reads = new HashSet<>();
    private boolean closed;

    AbstractSdkObjectStore(String bucket) { this.bucket = bucket; }

    final synchronized void checkOpen() {
        if (closed) throw new ApplicationFault("OBJECT_STORE_CLOSED", 503, "对象存储连接已关闭");
    }

    static void key(String key, boolean prefix) {
        if (key == null || (!prefix && key.isEmpty()) || key.startsWith("/") || key.contains("\\")
                || key.contains("//") || key.getBytes(StandardCharsets.UTF_8).length > 1024
                || key.codePoints().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        for (String part : key.split("/")) if (part.equals(".") || part.equals("..")) throw invalid();
    }

    static void page(String prefix, String delimiter, String token, int limit) {
        key(prefix, true);
        if ((delimiter != null && !delimiter.equals("/")) || limit < 1 || limit > 1000
                || (token != null && (token.length() > 8192 || token.codePoints().anyMatch(c -> c < 32)))) throw invalid();
    }

    static Map<String, String> metadata(String key, long size, String operationId, InputStream input) {
        key(key, false);
        if (input == null || size < 0 || size > MAX_OBJECT_SIZE)
            throw new ApplicationFault("OBJECT_STORE_SIZE", 413, "对象大小超过允许范围");
        if (operationId == null || !operationId.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        boolean probe = probeNonce(key) != null;
        if (key.contains(".studentcloud-probes") && !probe) throw invalid();
        return probe ? Map.of(OPERATION_METADATA, operationId, PROBE_METADATA, "true") : Map.of(OPERATION_METADATA, operationId);
    }

    static String validateProbe(Receipt receipt) {
        if (receipt == null || receipt.etag() == null || receipt.etag().isBlank() || receipt.size() < 0) throw StoreFailures.cleanupFailure();
        try { key(receipt.key(), false); } catch (ApplicationFault failure) { throw StoreFailures.cleanupFailure(); }
        String nonce = probeNonce(receipt.key());
        if (nonce == null) throw StoreFailures.cleanupFailure();
        return nonce;
    }

    static void verifyProbe(Receipt receipt, Item actual, Map<String, String> metadata, String nonce) {
        if (actual == null || metadata == null || !"true".equals(metadata.get(PROBE_METADATA))
                || !nonce.equals(metadata.get(OPERATION_METADATA)) || receipt.size() != actual.size()
                || !Objects.equals(etag(receipt.etag()), etag(actual.etag()))
                || !Objects.equals(receipt.versionId(), actual.versionId())) throw StoreFailures.cleanupFailure();
    }

    static String quotedEtag(String value) { return "\"" + etag(value) + "\""; }
    static String etag(String value) {
        if (value == null) return null;
        return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
    private static String probeNonce(String key) { Matcher matcher = PROBE.matcher(key); return matcher.matches() ? matcher.group(1) : null; }

    final synchronized InputStream track(InputStream source) {
        if (closed) {
            try { source.close(); } catch (IOException ignored) { }
            checkOpen();
        }
        InputStream wrapped = new FilterInputStream(source) {
            private boolean released;
            @Override public int read() throws IOException {
                try { return in.read(); } catch (IOException failure) { throw streamFailure(); }
                catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try { return in.read(bytes, offset, length); } catch (IOException failure) { throw streamFailure(); }
                catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
            }
            @Override public synchronized void close() throws IOException {
                if (released) return;
                released = true;
                try { in.close(); } catch (IOException failure) { throw streamFailure(); }
                catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
                finally { synchronized (AbstractSdkObjectStore.this) { reads.remove(this); } }
            }
        };
        reads.add(wrapped);
        return wrapped;
    }

    @Override public final void close() {
        Set<InputStream> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new HashSet<>(reads);
            reads.clear();
        }
        for (InputStream input : pending) try { input.close(); } catch (IOException | RuntimeException ignored) { }
        try { closeClient(); } catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    abstract void closeClient();
    private static IOException streamFailure() { return new IOException("对象存储读取未完成，请重新核对文件状态"); }
    private static ApplicationFault invalid() { return new ApplicationFault("OBJECT_STORE_REQUEST", 400, "对象存储路径或请求参数无效"); }
}

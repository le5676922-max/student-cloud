package io.studentcloud.storage;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import io.studentcloud.common.ApplicationFault;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class AliyunOssObjectStore extends AbstractSdkObjectStore {
    private final OSS client;

    AliyunOssObjectStore(Connection connection, Duration connect, Duration timeout, int maxConnections) {
        super(connection.bucket());
        ClientConfiguration config = new ClientConfiguration();
        config.setConnectionTimeout(Math.toIntExact(connect.toMillis()));
        config.setConnectionRequestTimeout(Math.toIntExact(connect.toMillis()));
        config.setSocketTimeout(Math.toIntExact(timeout.toMillis()));
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(Math.toIntExact(timeout.toMillis()));
        config.setMaxConnections(maxConnections);
        config.setMaxErrorRetry(0);
        config.setRedirectEnable(false);
        config.setEnableAutoCorrectClockSkew(false);
        config.setUseSystemPropertyValues(false);
        config.setSupportCname(false);
        config.setSLDEnabled(connection.pathStyle());
        config.setSignatureVersion(SignVersion.V4);
        client = OSSClientBuilder.create().endpoint(connection.endpoint().toString()).region(connection.region())
                .credentialsProvider(CredentialsProviderFactory.newDefaultCredentialProvider(connection.accessKey(), connection.secretKey(), connection.sessionToken()))
                .clientConfiguration(config).build();
    }

    @Override public Page list(String prefix, String delimiter, String token, int limit) {
        checkOpen(); page(prefix, delimiter, token, limit);
        try {
            ListObjectsV2Request request = new ListObjectsV2Request(bucket).withPrefix(prefix).withDelimiter(delimiter)
                    .withContinuationToken(token).withMaxKeys(limit);
            request.setLogEnabled(false);
            var response = client.listObjectsV2(request);
            List<Item> objects = response.getObjectSummaries().stream().map(value -> new Item(value.getKey(), value.getSize(),
                    value.getLastModified() == null ? null : value.getLastModified().toInstant(), value.getETag(), null)).toList();
            List<String> prefixes = response.getCommonPrefixes();
            if (objects.size() + prefixes.size() > limit) throw StoreFailures.status(502);
            return new Page(objects, prefixes, response.isTruncated() ? response.getNextContinuationToken() : null);
        } catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    @Override public Optional<Item> head(String key) {
        checkOpen(); key(key, false);
        try { return Optional.of(item(key, headMetadata(key, null))); }
        catch (OSSException failure) { if ("NoSuchKey".equals(failure.getErrorCode())) return Optional.empty(); throw StoreFailures.translate(failure); }
        catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    @Override public Read read(String key) {
        checkOpen(); key(key, false);
        OSSObject object = null;
        try {
            GetObjectRequest request = new GetObjectRequest(bucket, key);
            request.setLogEnabled(false);
            object = client.getObject(request);
            return new Read(item(key, object.getObjectMetadata()), track(object.getObjectContent()));
        } catch (RuntimeException failure) {
            if (object != null) try { object.forcedClose(); } catch (Exception ignored) { }
            throw StoreFailures.translate(failure);
        }
    }

    @Override public void requireCreateOnly() {
        checkOpen();
        try {
            GenericRequest request = new GenericRequest(bucket);
            request.setLogEnabled(false);
            String status = client.getBucketVersioning(request).getStatus();
            if (status != null && !status.isBlank() && !BucketVersioningConfiguration.OFF.equals(status))
                throw new ApplicationFault("OBJECT_STORE_VERSIONING_UNSUPPORTED", 409, "当前 OSS 版本控制状态不支持本应用的不覆盖写入策略");
        } catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    @Override public Receipt createOnly(String key, InputStream input, long size, String operationId) {
        boolean putAttempted = false;
        try {
            checkOpen(); Map<String, String> userMetadata = metadata(key, size, operationId, input);
            requireCreateOnly();
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(size);
            metadata.setContentType("application/octet-stream");
            metadata.setUserMetadata(userMetadata);
            PutObjectRequest request = new PutObjectRequest(bucket, key, input, metadata);
            request.addHeader("x-oss-forbid-overwrite", "true");
            request.setLogEnabled(false);
            putAttempted = true;
            PutObjectResult response = client.putObject(request);
            return new Receipt(key, size, response.getETag(), response.getVersionId());
        } catch (RuntimeException failure) { throw StoreFailures.write(failure, putAttempted); }
    }

    @Override public void cleanupProbe(Receipt receipt) {
        checkOpen(); String nonce = validateProbe(receipt);
        try {
            ObjectMetadata actual;
            try { actual = headMetadata(receipt.key(), receipt.versionId()); }
            catch (OSSException failure) { if ("NoSuchKey".equals(failure.getErrorCode()) || "NoSuchVersion".equals(failure.getErrorCode())) return; throw failure; }
            verifyProbe(receipt, item(receipt.key(), actual), userMetadata(actual), nonce);
            if (receipt.versionId() == null) {
                String status = client.getBucketVersioning(bucket).getStatus();
                if (status != null && !status.isBlank() && !BucketVersioningConfiguration.OFF.equals(status)) throw StoreFailures.cleanupFailure();
                GenericRequest request = new GenericRequest(bucket, receipt.key());
                request.addHeader("If-Match", quotedEtag(receipt.etag()));
                request.setLogEnabled(false);
                client.deleteObject(request);
            } else {
                DeleteVersionRequest request = new DeleteVersionRequest(bucket, receipt.key(), receipt.versionId());
                request.addHeader("If-Match", quotedEtag(receipt.etag()));
                request.setLogEnabled(false);
                client.deleteVersion(request);
            }
        } catch (ApplicationFault failure) { throw failure; }
        catch (RuntimeException failure) { throw StoreFailures.cleanupFailure(); }
    }

    private ObjectMetadata headMetadata(String key, String version) {
        HeadObjectRequest request = new HeadObjectRequest(bucket, key, version);
        request.setLogEnabled(false);
        return client.headObject(request);
    }
    private static Item item(String key, ObjectMetadata value) {
        return new Item(key, value.getContentLength(), value.getLastModified() == null ? null : value.getLastModified().toInstant(), value.getETag(), value.getVersionId(), userMetadata(value).get(OPERATION_METADATA));
    }
    private static Map<String, String> userMetadata(ObjectMetadata metadata) {
        // OSS SDK 3.x recognizes a lowercase prefix only; HTTP field names remain case-insensitive through proxies.
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        result.putAll(metadata.getUserMetadata());
        metadata.getRawMetadata().forEach((key, value) -> {
            if (key.regionMatches(true, 0, "x-oss-meta-", 0, 11) && value instanceof String text) result.put(key.substring(11), text);
        });
        return result;
    }
    @Override void closeClient() { client.shutdown(); }
}

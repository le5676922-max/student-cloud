package io.studentcloud.storage;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.ObjectStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class S3ObjectStore extends AbstractSdkObjectStore {
    private final S3Client client;

    S3ObjectStore(Connection connection, Duration connect, Duration timeout, int maxConnections) {
        super(connection.bucket());
        var credentials = connection.sessionToken() == null || connection.sessionToken().isBlank()
                ? AwsBasicCredentials.create(connection.accessKey(), connection.secretKey())
                : AwsSessionCredentials.create(connection.accessKey(), connection.secretKey(), connection.sessionToken());
        client = S3Client.builder().region(Region.of(connection.region())).endpointOverride(connection.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .crossRegionAccessEnabled(false).useArnRegion(false).disableMultiRegionAccessPoints(true)
                .forcePathStyle(connection.pathStyle())
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).retryPolicy(RetryPolicy.none()))
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(maxConnections).connectionTimeout(connect)
                        .connectionAcquisitionTimeout(connect).socketTimeout(timeout)
                        .proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariableValues(false).build()))
                .build();
    }

    @Override public Page list(String prefix, String delimiter, String token, int limit) {
        checkOpen(); page(prefix, delimiter, token, limit);
        try {
            var response = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                    .delimiter(delimiter).continuationToken(token).maxKeys(limit).build());
            List<Item> objects = response.contents().stream().map(value -> new Item(value.key(), value.size(), value.lastModified(), value.eTag(), null)).toList();
            List<String> prefixes = response.commonPrefixes().stream().map(CommonPrefix::prefix).toList();
            if (objects.size() + prefixes.size() > limit) throw StoreFailures.status(502);
            return new Page(objects, prefixes, Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null);
        } catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    @Override public Optional<Item> head(String key) {
        checkOpen(); key(key, false);
        try { return Optional.of(item(key, client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()))); }
        catch (S3Exception failure) { if (failure.statusCode() == 404) return Optional.empty(); throw StoreFailures.translate(failure); }
        catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    @Override public Read read(String key) {
        checkOpen(); key(key, false);
        ResponseInputStream<GetObjectResponse> input = null;
        try {
            input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            var response = input.response();
            Item item = new Item(key, response.contentLength(), response.lastModified(), response.eTag(), response.versionId(), response.metadata().get(OPERATION_METADATA));
            return new Read(item, track(input));
        } catch (RuntimeException failure) {
            if (input != null) input.abort();
            throw StoreFailures.translate(failure);
        }
    }

    @Override public void requireCreateOnly() { checkOpen(); /* Endpoint-specific conditional semantics are verified by the application probe. */ }

    @Override public Receipt createOnly(String key, InputStream input, long size, String operationId) {
        boolean putAttempted = false;
        try {
            checkOpen(); Map<String, String> metadata = metadata(key, size, operationId, input);
            try (RepeatableS3Body body = RepeatableS3Body.stage(input, size)) {
                putAttempted = true;
                var response = client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentLength(size)
                        .contentType("application/octet-stream").ifNoneMatch("*").metadata(metadata).build(), RequestBody.fromFile(body.file()));
                return new Receipt(key, size, response.eTag(), response.versionId());
            }
        } catch (RuntimeException failure) { throw StoreFailures.write(failure, putAttempted); }
    }

    @Override public void cleanupProbe(Receipt receipt) {
        checkOpen(); String nonce = validateProbe(receipt);
        try {
            HeadObjectResponse actual;
            try { actual = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(receipt.key()).versionId(receipt.versionId()).build()); }
            catch (S3Exception failure) { if (failure.statusCode() == 404) return; throw failure; }
            verifyProbe(receipt, item(receipt.key(), actual), actual.metadata(), nonce);
            if (receipt.versionId() == null) {
                String status = client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).statusAsString();
                if (status != null && !status.isBlank()) throw StoreFailures.cleanupFailure();
            }
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(receipt.key()).versionId(receipt.versionId())
                    .ifMatch(quotedEtag(receipt.etag())).build());
        } catch (ApplicationFault failure) { throw failure; }
        catch (RuntimeException failure) { throw StoreFailures.cleanupFailure(); }
    }

    private static Item item(String key, HeadObjectResponse value) {
        return new Item(key, value.contentLength(), value.lastModified(), value.eTag(), value.versionId(), value.metadata().get(OPERATION_METADATA));
    }
    @Override void closeClient() { client.close(); }
}

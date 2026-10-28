package io.studentcloud.storage;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.ObjectStore;
import io.studentcloud.drive.object.ObjectStoreFactory;

import java.net.URI;
import java.time.Duration;

/** SDK boundary only. The application must authorize the actor and approve/resolution-check the endpoint first. */
public final class SdkObjectStoreFactory implements ObjectStoreFactory {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private final Duration connectTimeout;
    private final Duration apiTimeout;
    private final int maxConnections;

    public SdkObjectStoreFactory() { this(CONNECT_TIMEOUT, API_TIMEOUT, 16); }

    SdkObjectStoreFactory(Duration connectTimeout, Duration apiTimeout, int maxConnections) {
        if (connectTimeout == null || apiTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || apiTimeout.isZero() || apiTimeout.isNegative() || maxConnections < 1 || maxConnections > 32)
            throw new IllegalArgumentException("Invalid object storage transport limits");
        this.connectTimeout = connectTimeout;
        this.apiTimeout = apiTimeout;
        this.maxConnections = maxConnections;
    }

    @Override public ObjectStore open(ObjectStore.Connection connection) {
        validate(connection);
        try {
            return switch (connection.provider()) {
                case ALIYUN_OSS -> new AliyunOssObjectStore(connection, connectTimeout, apiTimeout, maxConnections);
                case S3 -> new S3ObjectStore(connection, connectTimeout, apiTimeout, maxConnections);
            };
        } catch (ApplicationFault fault) { throw fault; }
        catch (RuntimeException failure) { throw StoreFailures.translate(failure); }
    }

    private static void validate(ObjectStore.Connection connection) {
        if (connection == null || connection.provider() == null) throw invalid();
        URI endpoint = connection.endpoint();
        if (endpoint == null || endpoint.isOpaque() || endpoint.getHost() == null
                || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty())
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535) throw invalid();
        if (connection.bucket() == null || !connection.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || connection.bucket().contains("..") || connection.region() == null
                || !connection.region().matches("[A-Za-z0-9-]{1,64}")
                || blank(connection.accessKey()) || blank(connection.secretKey())
                || connection.accessKey().length() > 512 || connection.secretKey().length() > 4096
                || (connection.sessionToken() != null && connection.sessionToken().length() > 16384)) throw invalid();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ApplicationFault invalid() {
        return new ApplicationFault("OBJECT_STORE_CONFIGURATION", 400, "对象存储连接配置不完整或格式无效");
    }
}

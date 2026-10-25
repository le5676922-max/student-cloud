package im.tlgxy.cloud.module.storage.service;

import com.alibaba.fastjson2.JSON;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.context.StorageSourceMutationGuard;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperClientFactory;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperEndpointPolicy;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.dto.TlgxyCloudCORSRule;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** No save/init calls: default preflight reads only; an explicit write probe verifies and cleans its own object. */
@Service
@RequiredArgsConstructor
public class StorageSourceSafetyService {
    private static final Set<StorageTypeEnum> S3_TYPES = Set.of(StorageTypeEnum.S3, StorageTypeEnum.ALIYUN,
            StorageTypeEnum.MINIO, StorageTypeEnum.HUAWEI, StorageTypeEnum.TENCENT, StorageTypeEnum.QINIU);
    private static final Set<String> METHODS = Set.of("GET", "PUT", "POST", "DELETE", "HEAD");
    private final TenantSecurityService security;
    private final TenantService tenants;
    private final StorageSourceConfigService configs;
    private final S3HelperEndpointPolicy endpointPolicy;
    private final S3HelperClientFactory clients;
    @Autowired(required = false)
    private WebDavReadOnlyProbe webDavProbe;
    @Autowired(required = false)
    private ResumableUploadProperties resumableUploads = new ResumableUploadProperties();
    @Autowired(required = false)
    private LocalUploadStagingPolicy stagingPolicy;
    private final Semaphore requests = new Semaphore(4);
    private final ThreadPoolExecutor localChecks = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "storage-local-preflight");
                thread.setDaemon(true);
                return thread;
            });

    public record PreflightResult(boolean passed, String message, boolean readable, Boolean writable, Boolean cleaned, String probeKey) {
        public PreflightResult(boolean passed, String message) { this(passed, message, passed, null, null, null); }
    }

    public PreflightResult testConnection(SaveStorageSourceRequest request) {
        var actor = security.requireStorageAdministrator();
        if (request == null || request.getType() == null || request.getStorageSourceAllParam() == null)
            throw new BizException("请选择存储类型并填写连接参数");
        if (request.getId() != null) security.requireManageStorage(request.getId());
        else {
            Integer tenantId = request.getTenantId() == null ? UserConstant.DEFAULT_TENANT_ID : request.getTenantId();
            if (actor.getRole() != im.tlgxy.cloud.module.user.model.enums.UserRoleEnum.ADMIN) {
                if (request.getTenantId() != null && !java.util.Objects.equals(request.getTenantId(), actor.getTenantId()))
                    throw new BizException("只能测试自己的工作空间连接");
                tenantId = actor.getTenantId();
            }
            tenants.getEnabledById(tenantId);
        }
        if (request.getType() == StorageTypeEnum.LOCAL && actor.getRole() != im.tlgxy.cloud.module.user.model.enums.UserRoleEnum.ADMIN)
            throw new BizException("本地云盘由全局管理员审批和验证，不能自行测试服务器路径");
        if (!requests.tryAcquire()) throw new BizException("连接测试繁忙，请稍后再试");
        try {
            if (request.getType() == StorageTypeEnum.LOCAL) return testLocal(request.getStorageSourceAllParam().getFilePath(), request.isVerifyWrite());
            if (request.getType() == StorageTypeEnum.WEBDAV) {
                if (webDavProbe == null) throw new BizException("WebDAV 安全预检服务不可用，请联系管理员");
                webDavProbe.assertReadable(request.getStorageSourceAllParam());
                if (request.isVerifyWrite()) return new PreflightResult(false,
                        "目录读取成功；当前 WebDAV 安全探针尚不支持带条件写入及清理验证，未写入文件，不能据此确认上传权限。", true, null, null, null);
                return new PreflightResult(true, "可读取 WebDAV 目录元数据；仅 PROPFIND Depth:0，未列出子文件、保存配置或写入文件。上传权限需另行验证。");
            }
            if (request.getType() == StorageTypeEnum.SFTP)
                throw new BizException("SFTP 暂不支持独立安全预检；保存时必须配置可信 SSH 主机密钥指纹，不能关闭主机校验");
            if (request.getType() == StorageTypeEnum.FTP)
                throw new BizException("FTP 暂不支持独立安全预检；明文 FTP 不推荐用于公网，不能用未校验的被动数据连接测试");
            if (!S3_TYPES.contains(request.getType())) throw new BizException("此类型尚不支持独立安全连接测试，请检查填写后保存；测试不会代替保存");
            StorageSourceAllParamDTO parameters = request.getStorageSourceAllParam();
            validateBucket(parameters.getBucketName());
            validateBasePath(parameters.getBasePath());
            var endpoint = endpointPolicy.validate(endpoint(parameters.getEndPoint(), parameters.getEndPointScheme()));
            String region = region(request.getType(), parameters.getRegion());
            requireCredentials(parameters.getAccessKey() == null ? parameters.getSecretId() : parameters.getAccessKey(), parameters.getSecretKey());
            try (var client = clients.create(endpoint, region,
                    parameters.getAccessKey() == null ? parameters.getSecretId() : parameters.getAccessKey(), parameters.getSecretKey())) {
                client.listObjectsV2(ListObjectsV2Request.builder().bucket(parameters.getBucketName())
                        .prefix(prefix(parameters.getBasePath())).maxKeys(1).build());
                if (request.isVerifyWrite()) return testS3Write(client, parameters);
            } catch (Exception exception) {
                throw new BizException("只读连接测试失败，请检查公网端点、区域、凭据和目录读取权限；未保存配置");
            }
            return new PreflightResult(true, "可读取目标目录；未保存配置，未写入文件或跨域规则。上传、删除等权限需另行验证。");
        } finally { requests.release(); }
    }

    private PreflightResult testLocal(String suppliedPath, boolean verifyWrite) {
        if (suppliedPath == null || suppliedPath.isBlank() || suppliedPath.length() > 2048
                || suppliedPath.startsWith("\\\\") || suppliedPath.startsWith("//")
                || suppliedPath.matches("(?s).*\\p{Cntrl}.*")) throw new BizException("请填写服务器上的本地绝对目录，不支持网络共享路径");
        Path directory;
        try {
            directory = Path.of(suppliedPath);
            if (!directory.isAbsolute() || !directory.normalize().equals(directory)) throw new IllegalArgumentException();
        } catch (Exception exception) { throw new BizException("本地目录格式不正确，不允许相对路径或路径回退"); }
        java.util.concurrent.Future<PreflightResult> future = null;
        try {
            future = localChecks.submit(() -> {
                (stagingPolicy == null ? new LocalUploadStagingPolicy(resumableUploads) : stagingPolicy).assertSeparated(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(directory))
                    throw new BizException("本地目录不存在、不是目录或不可读取；未创建目录");
                return verifyWrite ? testLocalWrite(directory) : new PreflightResult(true,
                        "本地目录存在且可读取；未列出文件、创建目录或保存配置。上传权限需另行验证。");
            });
            return future.get(3, TimeUnit.SECONDS);
        } catch (BizException exception) { throw exception; }
        catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (exception instanceof java.util.concurrent.ExecutionException && exception.getCause() instanceof BizException rejected)
                throw rejected;
            throw new BizException("本地目录检查未完成，请检查服务器目录权限后重试");
        } finally { if (future != null && !future.isDone()) future.cancel(true); }
    }

    private PreflightResult testLocalWrite(Path directory) {
        String token = UUID.randomUUID().toString();
        Path probe = directory.resolve(".student-cloud-probe-" + token + ".tmp");
        byte[] marker = ("Student Cloud connection test " + token).getBytes(StandardCharsets.UTF_8);
        boolean created = false, verified = false, cleaned = false;
        Object fileKey = null;
        try {
            try (var stream = Files.newOutputStream(probe, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                fileKey = Files.readAttributes(probe, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                stream.write(marker);
            }
            verified = Arrays.equals(marker, readLocalProbe(probe));
        } catch (Exception ignored) { /* Provider details and server paths are not exposed as exception text. */ }
        finally {
            if (created) try {
                var attributes = Files.readAttributes(probe, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && java.util.Objects.equals(fileKey, attributes.fileKey())
                        && attributes.size() <= marker.length
                        && Arrays.equals(Arrays.copyOf(marker, (int) attributes.size()), readLocalProbe(probe))) {
                    Files.delete(probe);
                    cleaned = !Files.exists(probe, LinkOption.NOFOLLOW_LINKS);
                }
            } catch (Exception ignored) { }
        }
        if (!created) return new PreflightResult(false, "目录可读，但无法创建测试文件；未确认上传权限。", true, false, null, null);
        return new PreflightResult(verified && cleaned, verified && cleaned
                ? "本地目录读取、随机小文件写入和回读校验成功；测试文件已删除，未保存配置。"
                : "本地文件测试未全部通过；" + (!cleaned ? "测试文件未确认清理，请全局管理员检查：" + probe.getFileName() : "回读校验失败，测试文件已删除。"),
                true, verified, cleaned, cleaned ? null : probe.getFileName().toString());
    }

    private static byte[] readLocalProbe(Path probe) throws java.io.IOException {
        try (var stream = Files.newInputStream(probe, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return stream.readNBytes(1025);
        }
    }

    /** Writes one 66-byte object, verifies exact content and metadata, deletes only that version/object. */
    private PreflightResult testS3Write(S3Client client, StorageSourceAllParamDTO parameters) {
        String token = UUID.randomUUID().toString();
        String base = prefix(parameters.getBasePath());
        if (!base.isEmpty() && !base.endsWith("/")) base += "/";
        String key = base + ".student-cloud-probe-" + token + ".tmp";
        byte[] marker = ("Student Cloud connection test " + token).getBytes(StandardCharsets.UTF_8);
        String version = null;
        boolean written = false, verified = false, cleaned = false;
        try {
            var response = client.putObject(PutObjectRequest.builder().bucket(parameters.getBucketName()).key(key)
                    .contentType("application/octet-stream").metadata(Map.of("student-cloud-probe", token))
                    .overrideConfiguration(config -> config.putHeader("If-None-Match", "*")).build(), RequestBody.fromBytes(marker));
            version = response.versionId(); written = true;
            try (var stream = client.getObject(GetObjectRequest.builder().bucket(parameters.getBucketName()).key(key)
                    .versionId(version).range("bytes=0-1024").build())) {
                verified = Arrays.equals(marker, stream.readNBytes(1025));
            }
        } catch (Exception ignored) { /* Even an ambiguous PUT result must attempt bounded ownership-safe cleanup. */ }
        finally {
            try {
                var head = client.headObject(HeadObjectRequest.builder().bucket(parameters.getBucketName()).key(key).versionId(version).build());
                if (token.equals(head.metadata().get("student-cloud-probe")) && head.contentLength() == marker.length) {
                    // PUT can time out after success. Recover the owned version from HEAD;
                    // deleting without this ID would leave the test version behind in versioned buckets.
                    if (version == null && head.versionId() != null) version = head.versionId();
                    client.deleteObject(DeleteObjectRequest.builder().bucket(parameters.getBucketName()).key(key).versionId(version)
                            .overrideConfiguration(config -> config.putHeader("If-Match", head.eTag())).build());
                    try { client.headObject(HeadObjectRequest.builder().bucket(parameters.getBucketName()).key(key).versionId(version).build()); }
                    catch (S3Exception absent) { if (absent.statusCode() == 404) cleaned = true; }
                }
            } catch (S3Exception absent) { if (absent.statusCode() == 404 && !written) cleaned = true; }
            catch (Exception ignored) { }
        }
        boolean success = written && verified && cleaned;
        return new PreflightResult(success, success
                ? "目录读取、随机小文件写入和回读校验成功；测试对象已确认删除，未保存配置或修改跨域规则。"
                : !cleaned ? "目录可读，但写入测试未全部完成；测试对象未确认清理，请检查对象：" + key
                : "目录可读，但写入或回读校验失败；未确认上传权限，测试对象已清理或确认未创建。",
                true, written && verified, cleaned, cleaned ? null : key);
    }

    /** Deliberately not transactional: remote mutation cannot be rolled back by the database. */
    public void applySavedCors(Integer storageId, boolean confirmed) {
        security.requireSystemAdmin();
        security.requireManageStorage(storageId);
        if (!confirmed) throw new BizException("请先明确确认覆盖远端跨域规则");
        try (var mutation = StorageSourceMutationGuard.acquire()) {
            var source = security.requireManageStorage(storageId);
            if (!S3_TYPES.contains(source.getType())) throw new BizException("当前存储类型不支持此跨域操作");
            Map<String, String> values = configs.selectFreshStorageConfigByStorageId(storageId).stream()
                    .collect(Collectors.toMap(StorageSourceConfig::getName, value -> value.getValue() == null ? "" : value.getValue(), (first, second) -> second));
            List<TlgxyCloudCORSRule> rules;
            try { rules = JSON.parseArray(values.getOrDefault("corsConfigList", "[]"), TlgxyCloudCORSRule.class); }
            catch (Exception exception) { throw new BizException("已保存的跨域规则格式不正确，请修正后保存"); }
            validateCors(rules);
            validateBucket(values.get("bucketName"));
            String accessKey = values.getOrDefault("accessKey", values.get("secretId"));
            requireCredentials(accessKey, values.get("secretKey"));
            var endpoint = endpointPolicy.validate(endpoint(values.get("endPoint"), values.get("endPointScheme")));
            String region = region(source.getType(), values.get("region"));
            if (!requests.tryAcquire()) throw new BizException("跨域操作繁忙，请稍后再试");
            try (var client = clients.create(endpoint, region, accessKey, values.get("secretKey"))) {
                client.putBucketCors(PutBucketCorsRequest.builder().bucket(values.get("bucketName"))
                        .corsConfiguration(CORSConfiguration.builder().corsRules(TlgxyCloudCORSRule.toCORSRule(rules)).build()).build());
            } catch (Exception exception) {
                throw new BizException("远端跨域应用结果未确认，请先读取远端规则核实，再决定是否重试");
            } finally { requests.release(); }
        }
    }

    private static String endpoint(String value, String scheme) {
        if (value == null || value.isBlank()) throw new BizException("请填写端点地址");
        if (value.contains("://")) return value;
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new BizException("请明确选择端点 HTTP/HTTPS 协议");
        return scheme + "://" + value;
    }
    private static String region(StorageTypeEnum type, String region) {
        if (type == StorageTypeEnum.ALIYUN) return "oss";
        if (type == StorageTypeEnum.HUAWEI) return "obs";
        if (type == StorageTypeEnum.TENCENT) return "cos";
        if (type == StorageTypeEnum.QINIU) return "kodo";
        if (region == null || !region.matches("[a-zA-Z0-9-]{1,64}")) throw new BizException("请填写有效区域，不自动猜测自定义端点的区域");
        return region;
    }
    private static void requireCredentials(String accessKey, String secretKey) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()
                || accessKey.length() > 512 || secretKey.length() > 4096) throw new BizException("请填写有效访问凭据");
    }
    private static void validateBucket(String bucket) {
        if (bucket == null || !bucket.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}")) throw new BizException("请填写有效存储桶名称");
    }
    private static void validateBasePath(String path) {
        if (path == null || path.isBlank()) return;
        if (path.length() > 2048 || !path.startsWith("/") || path.contains("\\") || path.contains("%")
                || path.matches("(?s).*\\p{Cntrl}.*") || java.util.Arrays.asList(path.split("/")).contains(".."))
            throw new BizException("目录前缀格式不正确，不允许路径回退");
    }
    private static String prefix(String path) { return path == null ? "" : path.replaceFirst("^/+", ""); }
    static void validateCors(List<TlgxyCloudCORSRule> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > 20) throw new BizException("请先保存 1 至 20 条有效跨域规则；此操作不删除远端规则");
        for (var rule : rules) {
            if (rule == null || rule.getAllowedMethods() == null || rule.getAllowedMethods().isEmpty()
                    || rule.getAllowedMethods().size() > 5 || !METHODS.containsAll(rule.getAllowedMethods())
                    || rule.getAllowedOrigins() == null || rule.getAllowedOrigins().isEmpty() || rule.getAllowedOrigins().size() > 20)
                throw new BizException("跨域规则的方法或来源配置不正确");
            for (String origin : rule.getAllowedOrigins()) {
                if ("*".equals(origin)) continue;
                try {
                    URI uri = URI.create(origin);
                    if (origin.length() > 512 || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                            || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                            || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())))
                        throw new IllegalArgumentException();
                } catch (Exception exception) { throw new BizException("跨域来源应为完整的 HTTP/HTTPS 来源或 *"); }
            }
            validateHeaders(rule.getAllowedHeaders());
            validateHeaders(rule.getExposedHeaders());
            if (rule.getId() != null && (rule.getId().length() > 255 || rule.getId().matches("(?s).*\\p{Cntrl}.*"))) throw new BizException("跨域规则标识不正确");
            if (rule.getMaxAgeSeconds() != null && (rule.getMaxAgeSeconds() < 0 || rule.getMaxAgeSeconds() > 86400)) throw new BizException("跨域预检缓存应为 0 至 86400 秒");
        }
    }
    private static void validateHeaders(List<String> headers) {
        if (headers != null && (headers.size() > 30 || headers.stream().anyMatch(header -> header == null || !header.matches("[a-zA-Z0-9*_-]{1,128}"))))
            throw new BizException("跨域请求头配置不正确");
    }
    @PreDestroy public void close() { localChecks.shutdownNow(); }
}

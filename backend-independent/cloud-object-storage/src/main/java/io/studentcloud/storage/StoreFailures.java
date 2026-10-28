package io.studentcloud.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.ObjectStore;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.SocketTimeoutException;
import java.util.Set;

/** Never attaches an SDK exception as a public cause: those may contain endpoint, key and signed headers. */
final class StoreFailures {
    private static final Set<String> AUTH = Set.of("AccessDenied", "AccessForbidden", "InvalidAccessKeyId", "SignatureDoesNotMatch",
            "InvalidSecurityToken", "SecurityTokenExpired", "InvalidToken", "AccessKeyIdAndSecurityTokenNotMatch");
    private static final Set<String> MISSING = Set.of("NoSuchKey", "NoSuchBucket", "NoSuchVersion", "NotFound");
    private static final Set<String> CONFLICT = Set.of("FileAlreadyExists", "PreconditionFailed", "ConditionalRequestConflict");
    private static final Set<String> INVALID = Set.of("InvalidArgument", "InvalidRequest", "InvalidObjectName", "InvalidBucketName", "EntityTooLarge");

    private StoreFailures() {}

    static ApplicationFault translate(Throwable failure) {
        if (failure instanceof ApplicationFault fault) return fault;
        if (failure instanceof S3Exception s3) return status(s3.statusCode());
        if (failure instanceof OSSException oss) {
            String code = oss.getErrorCode();
            if (AUTH.contains(code == null ? "" : code)) return status(403);
            if (MISSING.contains(code == null ? "" : code)) return status(404);
            if (CONFLICT.contains(code == null ? "" : code)) return status(409);
            if (INVALID.contains(code == null ? "" : code)) return status(400);
            if ("RequestTimeout".equals(code)) return status(504);
            if ("SlowDown".equals(code) || "Throttling".equals(code) || "ServiceUnavailable".equals(code)) return status(503);
            return status(502);
        }
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SocketTimeoutException || cursor instanceof ApiCallTimeoutException
                    || cursor instanceof ApiCallAttemptTimeoutException
                    || (cursor instanceof ClientException client && ("RequestTimeout".equals(client.getErrorCode())
                    || "ConnectionTimeout".equals(client.getErrorCode())))) return status(504);
        }
        return status(503);
    }

    static ApplicationFault status(int status) {
        if (status == 400) return new ApplicationFault("OBJECT_STORE_REJECTED", 400, "对象存储拒绝了请求，请检查配置或操作参数");
        if (status == 401 || status == 403) return new ApplicationFault("OBJECT_STORE_FORBIDDEN", 403, "对象存储认证或授权失败");
        if (status == 404) return new ApplicationFault("OBJECT_STORE_NOT_FOUND", 404, "对象存储资源不存在");
        if (status == 409 || status == 412) return new ApplicationFault("OBJECT_STORE_CONFLICT", 409, "目标对象已存在或发生并发冲突");
        if (status == 408 || status == 504) return new ApplicationFault("OBJECT_STORE_TIMEOUT", 504, "对象存储请求超时，请核对操作状态");
        if (status == 429 || status == 503) return new ApplicationFault("OBJECT_STORE_UNAVAILABLE", 503, "对象存储暂不可用，请稍后重试");
        return new ApplicationFault("OBJECT_STORE_RESPONSE", 502, "对象存储返回了无法处理的响应");
    }

    static ObjectStore.WriteFailure write(Throwable failure, boolean putAttempted) {
        if (failure instanceof ObjectStore.WriteFailure write) return write;
        ApplicationFault publicFault = translate(failure);
        boolean uncertain = putAttempted;
        if (failure instanceof S3Exception s3 && Set.of(400, 401, 403, 404, 409, 412, 413).contains(s3.statusCode())) uncertain = false;
        if (failure instanceof OSSException oss) {
            String code = oss.getErrorCode() == null ? "" : oss.getErrorCode();
            if (AUTH.contains(code) || MISSING.contains(code) || CONFLICT.contains(code) || INVALID.contains(code)) uncertain = false;
        }
        return new ObjectStore.WriteFailure(publicFault.code(), publicFault.httpStatus(), publicFault.getMessage(), uncertain);
    }

    static ApplicationFault cleanupFailure() {
        return new ApplicationFault("OBJECT_STORE_PROBE_CLEANUP", 502, "探针清理未完成，需要核对已登记的测试对象");
    }
}

package im.tlgxy.cloud.module.storage.service;
import im.tlgxy.cloud.core.exception.core.BizException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Shared wire contract for normal and resumable uploads; passwords never appear in URLs. */
public final class UploadDirectoryPassword {
    private UploadDirectoryPassword() { }
    public static String decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.length() > 9000 || encoded.chars().anyMatch(c -> c < 32 || c > 126)) throw new BizException("目录密码格式不合法");
        try {
            String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            if (decoded.length() > 1000) throw new BizException("目录密码过长");
            return decoded;
        } catch (IllegalArgumentException malformed) { throw new BizException("目录密码格式不合法"); }
    }
}

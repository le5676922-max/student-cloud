package im.tlgxy.cloud.core.util;

import cn.hutool.core.util.HexUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson2.JSON;
import im.tlgxy.cloud.module.config.service.SystemConfigService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Authenticated share tickets cannot be downgraded to unbound legacy download signatures. */
public final class ShareDownloadTokenUtils {
    private static final String PREFIX = "SHARE1.";
    private static final int MAX_TOKEN_LENGTH = 32768;

    private ShareDownloadTokenUtils() { }

    public record Ticket(Integer storageId, String path, String shareKey, long expiresAt) { }

    public static boolean isShareSignature(String signature) {
        return signature != null && signature.startsWith(PREFIX);
    }

    public static String generate(Integer storageId, String path, String shareKey, Integer effectiveSeconds) {
        if (storageId == null || shareKey == null || shareKey.isBlank()) {
            throw new IllegalArgumentException("A share download must identify its share and storage");
        }
        int lifetime = effectiveSeconds == null || effectiveSeconds < 1 ? 1800 : Math.min(effectiveSeconds, 1800);
        Ticket ticket = new Ticket(storageId, StringUtils.concat(path), shareKey, System.currentTimeMillis() + lifetime * 1000L);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.toJSONBytes(ticket));
        String authenticatedPayload = PREFIX + payload;
        return authenticatedPayload + "." + HexUtil.encodeHexStr(mac(authenticatedPayload));
    }

    /** Returns only MAC-verified, unexpired, path- and storage-bound metadata. */
    public static Ticket validate(Integer storageId, String path, String signature) {
        if (!isShareSignature(signature) || signature.length() > MAX_TOKEN_LENGTH) return null;
        try {
            String[] parts = signature.split("\\.", -1);
            if (parts.length != 3) return null;
            byte[] provided = HexUtil.decodeHex(parts[2]);
            if (!MessageDigest.isEqual(mac(PREFIX + parts[1]), provided)) return null;
            Ticket ticket = JSON.parseObject(Base64.getUrlDecoder().decode(parts[1]), Ticket.class);
            if (ticket == null || ticket.shareKey() == null || ticket.shareKey().isBlank()
                    || !java.util.Objects.equals(storageId, ticket.storageId())
                    || !java.util.Objects.equals(StringUtils.concat(path), ticket.path())
                    || System.currentTimeMillis() >= ticket.expiresAt()) return null;
            return ticket;
        } catch (Exception invalid) {
            return null;
        }
    }

    private static byte[] mac(String payload) {
        try {
            String key = SpringUtil.getBean(SystemConfigService.class).getAesHexKeyOrGenerate();
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(HexUtil.decodeHex(key), "HmacSHA256"));
            // Domain separation prevents other uses of the AES key from minting share tickets.
            return hmac.doFinal(("student-cloud-share-download\n" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            throw new IllegalStateException("Share download signing unavailable", unavailable);
        }
    }
}

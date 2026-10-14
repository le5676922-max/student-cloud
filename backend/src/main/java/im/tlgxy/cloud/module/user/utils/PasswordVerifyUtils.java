package im.tlgxy.cloud.module.user.utils;

import cn.hutool.crypto.SecureUtil;
import im.tlgxy.cloud.core.util.StringUtils;
import org.springframework.data.util.Pair;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author zhaojun
 */
public class PasswordVerifyUtils {

    private static final String HASH_PREFIX = "pbkdf2-sha256$";
    private static final int ITERATIONS = 310_000;
    private static final int MAX_VERIFY_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static boolean verify(String dbPassword, String dbSalt, String requestPassword) {
        if (dbPassword == null || requestPassword == null) {
            return false;
        }
        if (dbPassword.startsWith(HASH_PREFIX)) {
            return verifyPbkdf2(dbPassword, dbSalt, requestPassword);
        }
        // 根据是否有盐值, 选择加密方式（兼容旧版本没有盐值的情况）
        String encryptedPassword;
        if (StringUtils.isBlank(dbSalt)) {
            encryptedPassword = SecureUtil.md5(requestPassword);
        } else {
            String sha1Pwd = SecureUtil.sha1(requestPassword);
            encryptedPassword = SecureUtil.md5(sha1Pwd + dbSalt);
        }
        return MessageDigest.isEqual(dbPassword.getBytes(StandardCharsets.UTF_8),
                encryptedPassword.getBytes(StandardCharsets.UTF_8));
    }

    public static Pair<String, String> encrypt(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password must not be null");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String hash = Base64.getEncoder().encodeToString(deriveKey(password, salt, ITERATIONS));
        return Pair.of(HASH_PREFIX + ITERATIONS + "$" + hash, Base64.getEncoder().encodeToString(salt));
    }

    private static boolean verifyPbkdf2(String encodedHash, String encodedSalt, String password) {
        if (encodedSalt == null) {
            return false;
        }
        try {
            String[] parts = encodedHash.split("\\$", -1);
            if (parts.length != 3) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < ITERATIONS || iterations > MAX_VERIFY_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(encodedSalt);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) {
                return false;
            }
            return MessageDigest.isEqual(expected, deriveKey(password, salt, iterations));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] deriveKey(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}

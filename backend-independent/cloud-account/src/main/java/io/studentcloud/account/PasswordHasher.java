package io.studentcloud.account;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Self-describing, bounded PBKDF2 encoding independent of any legacy password representation. */
final class PasswordHasher {
    private static final int ITERATIONS = 310_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    String hash(String password) {
        if (!validNewPassword(password)) throw new IllegalArgumentException("Password must contain 8 to 128 characters");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    boolean verify(String password, String encoded) {
        if (password == null || password.length() > 128 || encoded == null || encoded.length() > 256) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
        try {
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < ITERATIONS || rounds > MAX_ITERATIONS) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) return false;
            return MessageDigest.isEqual(expected, derive(password, salt, rounds));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static boolean validNewPassword(String password) {
        return password != null && password.length() >= 8 && password.length() <= 128;
    }

    private static byte[] derive(String password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Password hashing is unavailable", unavailable);
        } finally {
            spec.clearPassword();
        }
    }
}

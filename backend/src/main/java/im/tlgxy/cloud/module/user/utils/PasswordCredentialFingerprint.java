package im.tlgxy.cloud.module.user.utils;

import im.tlgxy.cloud.module.user.model.entity.User;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** A server-only token marker, not a password or a browser-visible authentication credential. */
public final class PasswordCredentialFingerprint {
    public static final String SESSION_KEY = "cloud:credential-fingerprint";
    private PasswordCredentialFingerprint() { }

    public static String of(User user) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (user.getId() + "\0" + user.getPassword() + "\0" + user.getSalt()).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean matches(String fingerprint, User user) {
        return fingerprint != null && MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                of(user).getBytes(StandardCharsets.US_ASCII));
    }
}

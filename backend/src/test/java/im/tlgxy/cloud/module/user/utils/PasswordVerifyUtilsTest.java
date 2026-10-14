package im.tlgxy.cloud.module.user.utils;

import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordVerifyUtilsTest {
    @Test
    void newHashesUseRandomSaltAndRejectWrongPasswords() {
        var first = PasswordVerifyUtils.encrypt("correct-password-测试");
        var second = PasswordVerifyUtils.encrypt("correct-password-测试");
        assertTrue(first.getFirst().startsWith("pbkdf2-sha256$310000$"));
        assertEquals(16, Base64.getDecoder().decode(first.getSecond()).length);
        assertNotEquals(first.getSecond(), second.getSecond());
        assertNotEquals(first.getFirst(), second.getFirst());
        assertTrue(PasswordVerifyUtils.verify(first.getFirst(), first.getSecond(), "correct-password-测试"));
        assertFalse(PasswordVerifyUtils.verify(first.getFirst(), first.getSecond(), "wrong-password"));
    }

    @Test
    void existingSaltedAndUnsaltedMd5PasswordsRemainCompatible() {
        String password = "legacy-password-123";
        String salt = "legacy-random-salt";
        String salted = SecureUtil.md5(SecureUtil.sha1(password) + salt);
        assertTrue(PasswordVerifyUtils.verify(salted, salt, password));
        assertFalse(PasswordVerifyUtils.verify(salted, salt, "incorrect"));
        assertTrue(PasswordVerifyUtils.verify(SecureUtil.md5(password), null, password));
        assertTrue(PasswordVerifyUtils.verify(SecureUtil.md5(password), "", password));
        assertFalse(PasswordVerifyUtils.verify(SecureUtil.md5(password), null, "incorrect"));
    }

    @Test
    void malformedHashesAndExcessiveWorkFactorsFailClosed() {
        var encrypted = PasswordVerifyUtils.encrypt("test-password");
        assertFalse(PasswordVerifyUtils.verify(null, null, "test-password"));
        assertFalse(PasswordVerifyUtils.verify(encrypted.getFirst(), encrypted.getSecond(), null));
        assertFalse(PasswordVerifyUtils.verify(encrypted.getFirst(), null, "test-password"));
        assertFalse(PasswordVerifyUtils.verify(encrypted.getFirst(), "invalid-base64!", "test-password"));
        assertFalse(PasswordVerifyUtils.verify("pbkdf2-sha256$310000$invalid!", encrypted.getSecond(), "test-password"));
        assertFalse(PasswordVerifyUtils.verify(encrypted.getFirst().replace("310000", "1"), encrypted.getSecond(), "test-password"));
        assertFalse(PasswordVerifyUtils.verify(encrypted.getFirst().replace("310000", "2147483647"), encrypted.getSecond(), "test-password"));
        assertFalse(PasswordVerifyUtils.verify(encrypted.getFirst() + "$extra", encrypted.getSecond(), "test-password"));
    }
}

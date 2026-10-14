package im.tlgxy.cloud.module.storage.support.sftp;

import com.jcraft.jsch.HostKeyRepository;
import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PinnedHostKeyRepositoryTest {
    private final byte[] key = "synthetic-ssh-wire-key-not-a-real-provider".getBytes(StandardCharsets.UTF_8);

    @Test void acceptsOnlyExpectedHostAndFingerprintIncludingNonStandardPort() throws Exception {
        var repository = new PinnedHostKeyRepository("sftp.example.test", 2222, fingerprint());
        assertEquals(HostKeyRepository.OK, repository.check("[sftp.example.test]:2222", key));
        assertEquals(HostKeyRepository.OK, repository.check("sftp.example.test", key));
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("other.example.test", key));
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("[sftp.example.test]:22", key));
        assertEquals(HostKeyRepository.CHANGED, repository.check("sftp.example.test", "different-key".getBytes()));
    }

    @Test void missingMalformedMd5AndEmptyKeyNeverBecomeTrustOnFirstUse() throws Exception {
        for (String value : new String[]{null, "", "MD5:bad", "SHA256:not-a-valid-pin"})
            assertThrows(BizException.class, () -> new PinnedHostKeyRepository("sftp.example.test", 22, value));
        var repository = new PinnedHostKeyRepository("sftp.example.test", 22, fingerprint());
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("sftp.example.test", new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> repository.add(null, null));
        assertThrows(UnsupportedOperationException.class, () -> repository.remove("sftp.example.test", "ssh-rsa"));
    }

    @Test void factoryRefusesUnpinnedOldConfigurationBeforeAnyConnection() {
        var error = assertThrows(BizException.class, () -> new SFtpClientFactory("sftp.example.test", 22,
                "synthetic-user", "synthetic-password", null, null, StandardCharsets.UTF_8));
        assertTrue(error.getMessage().contains("指纹"));
        assertFalse(error.getMessage().contains("synthetic-password"));
    }

    private String fingerprint() throws Exception {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
    }
}

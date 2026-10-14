package im.tlgxy.cloud.module.storage.support.sftp;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import im.tlgxy.cloud.core.exception.core.BizException;

import java.security.MessageDigest;
import java.util.Base64;

/** Trust exactly one administrator-verified SHA256 host key; never perform trust-on-first-use. */
public final class PinnedHostKeyRepository implements HostKeyRepository {
    private final String host;
    private final int port;
    private final byte[] digest;

    public PinnedHostKeyRepository(String host, int port, String fingerprint) {
        if (host == null || host.isBlank() || host.length() > 253 || host.matches("(?s).*\\p{Cntrl}.*")
                || port < 1 || port > 65535) throw new BizException("SFTP 主机或端口格式不正确");
        this.host = host.trim().replace("[", "").replace("]", "");
        this.port = port;
        if (fingerprint == null || !fingerprint.trim().matches("SHA256:[A-Za-z0-9+/]{43}=?"))
            throw new BizException("SFTP 必须填写由可信渠道核实的 SSH 主机 SHA256 指纹，不允许自动信任首次连接");
        try {
            digest = Base64.getDecoder().decode(fingerprint.trim().substring("SHA256:".length()));
            if (digest.length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new BizException("SFTP 主机 SHA256 指纹格式不正确"); }
    }

    @Override public int check(String candidateHost, byte[] key) {
        if (candidateHost == null || key == null || key.length == 0 || key.length > 16384) return NOT_INCLUDED;
        if (!host.equalsIgnoreCase(candidateHost) && !("[" + host + "]:" + port).equalsIgnoreCase(candidateHost))
            return NOT_INCLUDED;
        try {
            return MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(key)) ? OK : CHANGED;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    @Override public void add(HostKey key, UserInfo info) { throw new UnsupportedOperationException("Automatic SSH host trust is forbidden"); }
    @Override public void remove(String host, String type) { throw new UnsupportedOperationException("Pinned host trust cannot be removed at runtime"); }
    @Override public void remove(String host, String type, byte[] key) { throw new UnsupportedOperationException("Pinned host trust cannot be removed at runtime"); }
    @Override public String getKnownHostsRepositoryID() { return "student-cloud-admin-verified-sha256-pin"; }
    @Override public HostKey[] getHostKey() { return new HostKey[0]; }
    @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
}

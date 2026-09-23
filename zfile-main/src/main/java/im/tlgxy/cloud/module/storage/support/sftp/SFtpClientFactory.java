package im.tlgxy.cloud.module.storage.support.sftp;

import cn.hutool.extra.ssh.Sftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.constant.StorageSourceConnectionProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * SFTP 客户端对象工厂 —— 用于对象池中创建、验证和销毁 SFTP 连接.
 * <p>
 * 该类继承自 Apache Commons Pool2 的 {@link BasePooledObjectFactory}，
 * 实现了 SFTP（SSH File Transfer Protocol）客户端连接对象的生命周期管理。
 * 与 FTP 不同，SFTP 基于 SSH 协议进行安全的文件传输，支持两种认证方式：
 * <ul>
 *   <li><b>密码认证</b>：使用用户名和密码进行登录</li>
 *   <li><b>密钥认证</b>：使用 SSH 私钥（可附带私钥密码 passphrase）进行登录，更加安全</li>
 * </ul>
 * </p>
 * <p>
 * 主要职责包括：
 * <ul>
 *   <li><b>创建（create）</b>：根据配置参数（主机、端口、用户名、密码或私钥）创建新的 SFTP 连接</li>
 *   <li><b>包装（wrap）</b>：将 SFTP 客户端对象包装为池化管理对象</li>
 *   <li><b>验证（validateObject）</b>：通过执行 pwd 命令验证连接是否仍然有效</li>
 *   <li><b>销毁（destroyObject）</b>：关闭 SFTP 连接，释放底层 SSH 会话和网络资源</li>
 * </ul>
 * </p>
 * <p>
 * 注意：该类在静态初始化块中设置了 JSch 库的 {@code StrictHostKeyChecking} 为 "no"，
 * 即跳过 SSH 主机密钥的严格校验。这是为了方便连接那些首次连接或主机密钥未在 known_hosts
 * 中记录的服务器，但在生产环境中应当注意这种方式可能存在中间人攻击的风险。
 * </p>
 *
 * @see SFtpClientPool SFTP 客户端对象池
 */
@Slf4j
public class SFtpClientFactory extends BasePooledObjectFactory<Sftp> {

    /**
     * SFTP 服务器主机地址.
     */
    private final String host;

    /**
     * SFTP 服务器端口号（默认为 22）.
     */
    private final int port;

    /**
     * SFTP 登录用户名.
     */
    private final String username;

    /**
     * SFTP 登录密码（密码认证方式使用）.
     * 如果使用私钥认证，此字段可能为 null。
     */
    private final String password;

    /**
     * SSH 私钥内容（密钥认证方式使用）.
     * 如果使用密码认证，此字段为空。
     */
    private final String privateKey;

    /**
     * SSH 私钥的密码短语（passphrase）.
     * 如果私钥设置了密码保护，则需要提供此字段；否则可为 null。
     */
    private final String passphrase;

    /**
     * SFTP 连接使用的字符编码.
     */
    private final Charset charset;

    /**
     * 静态初始化块，配置 JSch（Java Secure Channel）库的全局参数.
     * <p>
     * 将 {@code StrictHostKeyChecking} 设置为 "no"，表示不进行严格的主机密钥检查。
     * 这意味着客户端会接受任何主机密钥，即使它不在 known_hosts 文件中。
     * 这在自托管或内网环境中可以简化连接配置，但在公网环境中应注意安全性。
     * </p>
     */
    static {
        JSch.setConfig("StrictHostKeyChecking", "no");
    }

    /**
     * 构造函数，初始化 SFTP 客户端工厂所需的全部连接参数.
     *
     * @param host       SFTP 服务器主机地址
     * @param port       SFTP 服务器端口号
     * @param username   SFTP 登录用户名
     * @param password   SFTP 登录密码（密码认证方式使用，密钥认证时可为 null）
     * @param privateKey SSH 私钥内容（密钥认证方式使用，密码认证时可为空）
     * @param passphrase SSH 私钥的密码短语（如果私钥有密码保护；否则可为 null）
     * @param charset    字符编码
     */
    public SFtpClientFactory(String host, int port, String username, String password, String privateKey, String passphrase, Charset charset) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKey = privateKey;
        this.passphrase = passphrase;
        this.charset = charset;
    }

    /**
     * 创建一个新的 SFTP 客户端连接对象.
     * <p>
     * 该方法由对象池在需要新连接时调用。认证方式根据参数自动选择：
     * <ul>
     *   <li>如果未提供私钥（privateKey 为空），则使用密码认证方式</li>
     *   <li>如果提供了私钥，则使用密钥认证方式，并可选提供私钥的密码短语（passphrase）</li>
     * </ul>
     * 创建过程：
     * <ol>
     *   <li>创建 JSch 实例并建立 SSH 会话（Session）</li>
     *   <li>设置连接超时时间</li>
     *   <li>根据认证方式进行身份验证</li>
     *   <li>在 SSH 会话基础上创建 SFTP 客户端</li>
     * </ol>
     * </p>
     *
     * @return 新创建的 SFTP 客户端连接对象
     * @throws Exception 如果创建连接或认证过程中发生错误
     */
    @Override
    public Sftp create() throws Exception {
        // 创建 JSch 实例并建立 SSH 会话
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        // 设置会话连接超时时间
        session.setTimeout(StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_MILLIS);

        // 判断认证方式：私钥为空则使用密码认证
        if (StringUtils.isBlank(privateKey)) {
            // 密码认证方式
            session.setPassword(password);
        } else {
            // 密钥认证方式
            byte[] passphraseBytes = null;
            // 如果提供了私钥密码（passphrase），将其转换为字节数组
            if (passphrase != null && !passphrase.isEmpty()) {
                passphraseBytes = passphrase.getBytes(StandardCharsets.UTF_8);
            }
            // 将私钥添加到 JSch 的身份认证中
            jsch.addIdentity(username, privateKey.getBytes(StandardCharsets.UTF_8), null, passphraseBytes);
        }

        // 基于已认证的 SSH 会话创建 SFTP 客户端
        Sftp sftp = new Sftp(session, charset, StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_MILLIS);
        log.debug("Creating object: {}", sftp);
        return sftp;
    }

    /**
     * 将 SFTP 客户端对象包装为池化管理对象.
     * <p>
     * {@link DefaultPooledObject} 是 Apache Commons Pool2 提供的默认池对象包装器，
     * 负责跟踪对象的使用状态。
     * </p>
     *
     * @param sftpClient SFTP 客户端连接对象
     * @return 包装后的池化管理对象
     */
    @Override
    public PooledObject<Sftp> wrap(Sftp sftpClient) {
        return new DefaultPooledObject(sftpClient);
    }

    /**
     * 验证池中的 SFTP 连接是否仍然有效.
     * <p>
     * 通过向 SFTP 服务器发送 pwd（打印当前工作目录）命令来判断连接状态：
     * 如果命令执行成功，说明连接正常；如果抛出异常，说明连接已断开或会话已过期。
     * 无效的连接将被池自动销毁并替换为新的连接。
     * </p>
     *
     * @param p 包装后的池化 SFTP 客户端对象
     * @return true 表示连接有效，false 表示连接已失效
     */
    @Override
    public boolean validateObject(PooledObject<Sftp> p) {
        String pwd = null;
        try {
            // 尝试执行 pwd 命令来验证连接是否存活
            pwd = p.getObject().pwd();
        } catch (Exception fex) {
            // 忽略验证过程中的异常（例如连接已断开或 SSH 会话已过期）
            // ignore
        }
        // 如果成功获取到当前目录路径，说明连接有效
        boolean isValid = pwd != null;
        log.debug("Validating object: {} isValid: {}", p.getObject(), isValid);
        return isValid;
    }

    /**
     * 销毁（关闭）SFTP 客户端连接对象.
     * <p>
     * 该方法在对象从池中移除时被调用（如连接过期、空闲超时或验证失败时），
     * 负责关闭 SFTP 连接以及底层的 SSH 会话，释放所有网络和系统资源。
     * </p>
     *
     * @param p 包装后的池化 SFTP 客户端对象
     * @throws Exception 如果关闭连接过程中发生错误
     */
    @Override
    public void destroyObject(PooledObject<Sftp> p) throws Exception {
        // 关闭 SFTP 连接和底层 SSH 会话
        p.getObject().close();
        log.debug("Destroying object: {}", p.getObject());
    }

}
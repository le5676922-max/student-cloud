package im.tlgxy.cloud.module.storage.support.ftp;

import cn.hutool.extra.ftp.Ftp;
import cn.hutool.extra.ftp.FtpConfig;
import cn.hutool.extra.ftp.FtpMode;
import im.tlgxy.cloud.module.storage.constant.StorageSourceConnectionProperties;
import im.tlgxy.cloud.module.storage.service.impl.FtpServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.nio.charset.Charset;

/**
 * FTP 客户端对象工厂 —— 用于对象池中创建、验证和销毁 FTP 连接.
 * <p>
 * 该类继承自 Apache Commons Pool2 的 {@link BasePooledObjectFactory}，
 * 实现了 FTP 客户端连接对象的生命周期管理。主要职责包括：
 * <ul>
 *   <li><b>创建（create）</b>：根据配置参数创建新的 FTP 连接，设置连接超时、传输模式、文件类型等</li>
 *   <li><b>包装（wrap）</b>：将 FTP 客户端对象包装为池化管理对象 {@link DefaultPooledObject}</li>
 *   <li><b>验证（validateObject）</b>：在执行操作前验证连接是否仍然有效（通过执行 pwd 命令检测）</li>
 *   <li><b>销毁（destroyObject）</b>：关闭 FTP 连接，释放资源</li>
 * </ul>
 * </p>
 * <p>
 * 使用场景：通过对象池复用 FTP 连接，避免频繁创建和销毁连接的开销，提高性能和资源利用率。
 * 当需要操作 FTP 服务器时，从池中借用一个连接，使用完毕后归还，而非每次重新建立连接。
 * </p>
 *
 * @see FtpClientPool FTP 客户端对象池
 */
@Slf4j
public class FtpClientFactory extends BasePooledObjectFactory<Ftp> {

    /**
     * FTP 服务器主机地址.
     */
    private final String host;

    /**
     * FTP 服务器端口号.
     */
    private final int port;

    /**
     * FTP 登录用户名.
     */
    private final String username;

    /**
     * FTP 登录密码.
     */
    private final String password;

    /**
     * FTP 连接使用的字符编码.
     */
    private final Charset charset;

    /**
     * FTP 传输模式：Active（主动模式）或 Passive（被动模式）.
     */
    private final String ftpMode;

    /**
     * 构造函数，初始化 FTP 客户端工厂所需的连接参数.
     *
     * @param host     FTP 服务器主机地址
     * @param port     FTP 服务器端口号
     * @param username FTP 登录用户名
     * @param password FTP 登录密码
     * @param charset  字符编码
     * @param ftpMode  FTP 传输模式（"Active" 或 "Passive"）
     */
    public FtpClientFactory(String host, int port, String username, String password, Charset charset, String ftpMode) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.charset = charset;
        this.ftpMode = ftpMode;
    }

    /**
     * 创建一个新的 FTP 客户端连接对象.
     * <p>
     * 该方法由对象池在需要新连接时调用。会配置以下参数：
     * <ul>
     *   <li>连接超时时间：使用系统默认的连接超时配置</li>
     *   <li>传输模式：根据 ftpMode 参数选择主动模式（Active）或被动模式（Passive）</li>
     *   <li>文件传输类型：设置为二进制模式（BINARY），确保文件传输不损坏</li>
     *   <li>隐藏文件显示：设置为显示隐藏文件</li>
     * </ul>
     * </p>
     *
     * @return 新创建的 FTP 客户端连接对象
     * @throws Exception 如果创建连接过程中发生错误
     */
    @Override
    public Ftp create() throws Exception {
        // 构建 FTP 连接配置
        FtpConfig ftpConfig = new FtpConfig(host, port, username, password, charset);
        // 设置连接超时时间
        ftpConfig.setConnectionTimeout(StorageSourceConnectionProperties.DEFAULT_CONNECTION_TIMEOUT_MILLIS);
        // 根据配置选择 FTP 传输模式：Active（主动）或 Passive（被动）
        Ftp ftp = new Ftp(ftpConfig, FtpServiceImpl.FTP_MODE_ACTIVE.equals(ftpMode) ? FtpMode.Active : FtpMode.Passive);
        // 设置文件传输类型为二进制模式，避免文本模式导致文件损坏
        ftp.getClient().setFileType(FTP.BINARY_FILE_TYPE);
        // 设置显示隐藏文件，以便列出所有文件和文件夹
        ftp.getClient().setListHiddenFiles(true);
        log.debug("Creating object: {}", ftp);
        return ftp;
    }

    /**
     * 将 FTP 客户端对象包装为池化管理对象.
     * <p>
     * {@link DefaultPooledObject} 是 Apache Commons Pool2 提供的默认池对象包装器，
     * 负责跟踪对象的使用状态（如借出/归还次数、创建时间等）。
     * </p>
     *
     * @param ftpClient FTP 客户端连接对象
     * @return 包装后的池化管理对象
     */
    @Override
    public PooledObject<Ftp> wrap(Ftp ftpClient) {
        return new DefaultPooledObject(ftpClient);
    }

    /**
     * 验证池中的 FTP 连接是否仍然有效.
     * <p>
     * 该方法在对象从池中借出前被调用（取决于池的配置），用于检测连接是否仍然存活。
     * 通过向 FTP 服务器发送 pwd（打印当前工作目录）命令来判断连接状态：
     * 如果命令执行成功，说明连接正常；如果抛出异常，说明连接已断开。
     * </p>
     *
     * @param p 包装后的池化 FTP 客户端对象
     * @return true 表示连接有效，false 表示连接已失效（将被销毁并重新创建）
     */
    @Override
    public boolean validateObject(PooledObject<Ftp> p) {
        String pwd = null;
        try {
            // 尝试执行 pwd 命令来验证连接是否存活
            pwd = p.getObject().pwd();
        } catch (Exception fex) {
            // 忽略验证过程中的异常（例如连接已断开）
            // ignore
        }
        // 如果成功获取到当前目录路径，说明连接有效
        boolean isValid = pwd != null;
        log.debug("Validating object: {} isValid: {}", p.getObject(), isValid);
        return isValid;
    }

    /**
     * 销毁（关闭）FTP 客户端连接对象.
     * <p>
     * 该方法在对象从池中移除时被调用（如连接过期、空闲超时或验证失败时），
     * 负责关闭 FTP 连接并释放底层网络资源。
     * </p>
     *
     * @param p 包装后的池化 FTP 客户端对象
     * @throws Exception 如果关闭连接过程中发生错误
     */
    @Override
    public void destroyObject(PooledObject<Ftp> p) throws Exception {
        // 关闭 FTP 连接，释放底层 socket 资源
        p.getObject().close();
        log.debug("Destroying object: {}", p.getObject());
    }

}
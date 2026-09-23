package im.tlgxy.cloud.module.storage.support.sftp;

import cn.hutool.extra.ssh.Sftp;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * SFTP 客户端连接池 —— 管理 SFTP 连接的复用和生命周期.
 * <p>
 * 该类继承自 Apache Commons Pool2 的 {@link GenericObjectPool}，专门用于管理
 * {@link Sftp} 类型对象的连接池。通过对象池模式复用 SFTP 连接，核心优势包括：
 * <ul>
 *   <li><b>连接复用</b>：避免频繁创建和销毁 SFTP 连接，减少 SSH 握手和认证开销。
 *       SFTP 连接比普通 FTP 连接更加"昂贵"，因为其底层是 SSH 会话，涉及密钥交换和加密协商</li>
 *   <li><b>资源限制</b>：通过配置最大连接数、最大空闲连接数等参数，防止 SFTP 连接数过多
 *       导致服务器端 SSH 连接资源耗尽</li>
 *   <li><b>连接健康检查</b>：配合 {@link SFtpClientFactory#validateObject} 方法，
 *       自动检测并剔除因 SSH 会话过期或网络断开而失效的连接</li>
 *   <li><b>空闲回收</b>：自动回收长时间空闲的连接，释放服务器端 SSH 会话资源</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * SFtpClientFactory factory = new SFtpClientFactory(host, port, username, password, null, null, charset);
 * GenericObjectPoolConfig<Sftp> config = new GenericObjectPoolConfig<>();
 * config.setMaxTotal(5);  // SFTP 连接建议适当限制数量
 * SFtpClientPool pool = new SFtpClientPool(factory, config);
 * Sftp sftp = pool.borrowObject();
 * try {
 *     // 使用 sftp 进行文件操作
 * } finally {
 *     pool.returnObject(sftp);
 * }
 * }</pre>
 * </p>
 *
 * @see SFtpClientFactory SFTP 客户端对象工厂，负责创建、验证和销毁连接
 */
public class SFtpClientPool extends GenericObjectPool<Sftp> {

    /**
     * 使用默认配置创建 SFTP 客户端连接池.
     * <p>
     * 该构造方法使用 {@link GenericObjectPool} 的默认配置参数（如最大连接数为 8、最大空闲连接数为 8 等）。
     * 适用于对连接池参数没有特殊要求的简单场景。
     * </p>
     *
     * @param factory SFTP 客户端对象工厂，负责创建和管理 SFTP 连接的生命周期
     */
    public SFtpClientPool(PooledObjectFactory<Sftp> factory) {
        super(factory);
    }

    /**
     * 使用自定义配置创建 SFTP 客户端连接池.
     * <p>
     * 该构造方法允许传入自定义的 {@link GenericObjectPoolConfig} 配置对象，
     * 以精细化控制连接池的行为，包括：
     * <ul>
     *   <li>最大连接数（maxTotal）：限制同时存在的 SFTP 连接数量</li>
     *   <li>最大空闲连接数（maxIdle）：限制池中保持的最大空闲连接数</li>
     *   <li>最小空闲连接数（minIdle）：池中始终保持的最小空闲连接数，预热连接</li>
     *   <li>连接最大等待时间（maxWaitMillis）：当池中无可用连接时，等待的最大时间</li>
     *   <li>借出时是否验证连接（testOnBorrow）：每次借出前验证连接有效性</li>
     *   <li>归还时是否验证连接（testOnReturn）：每次归还后验证连接有效性</li>
     *   <li>空闲时是否验证连接（testWhileIdle）：定期检查空闲连接的健康状态</li>
     *   <li>逐出检查间隔（timeBetweenEvictionRunsMillis）：后台线程检查并清理无效连接的周期</li>
     * </ul>
     * </p>
     *
     * @param factory SFTP 客户端对象工厂，负责创建和管理 SFTP 连接的生命周期
     * @param config  连接池的自定义配置对象
     */
    public SFtpClientPool(PooledObjectFactory<Sftp> factory, GenericObjectPoolConfig config) {
        super(factory, config);
    }

}
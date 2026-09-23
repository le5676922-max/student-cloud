package im.tlgxy.cloud.module.storage.support.ftp;

import cn.hutool.extra.ftp.Ftp;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * FTP 客户端连接池 —— 管理 FTP 连接的复用和生命周期.
 * <p>
 * 该类继承自 Apache Commons Pool2 的 {@link GenericObjectPool}，专门用于管理
 * {@link Ftp} 类型对象的连接池。通过对象池模式复用 FTP 连接，核心优势包括：
 * <ul>
 *   <li><b>连接复用</b>：避免频繁创建和销毁 FTP 连接，减少 TCP 握手和认证开销</li>
 *   <li><b>资源限制</b>：通过配置最大连接数、最大空闲连接数等参数，防止资源耗尽</li>
 *   <li><b>连接健康检查</b>：配合 {@link FtpClientFactory#validateObject} 方法，
 *       自动检测并剔除失效连接</li>
 *   <li><b>空闲回收</b>：自动回收长时间空闲的连接，释放服务器端资源</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * FtpClientFactory factory = new FtpClientFactory(host, port, username, password, charset, mode);
 * GenericObjectPoolConfig<Ftp> config = new GenericObjectPoolConfig<>();
 * config.setMaxTotal(10);
 * FtpClientPool pool = new FtpClientPool(factory, config);
 * Ftp ftp = pool.borrowObject();
 * try {
 *     // 使用 ftp 进行操作
 * } finally {
 *     pool.returnObject(ftp);
 * }
 * }</pre>
 * </p>
 *
 * @see FtpClientFactory FTP 客户端对象工厂，负责创建、验证和销毁连接
 */
public class FtpClientPool extends GenericObjectPool<Ftp> {

    /**
     * 使用默认配置创建 FTP 客户端连接池.
     * <p>
     * 该构造方法使用 {@link GenericObjectPool} 的默认配置参数（如最大连接数为 8、最大空闲连接数为 8 等）。
     * 适用于对连接池参数没有特殊要求的简单场景。
     * </p>
     *
     * @param factory FTP 客户端对象工厂，负责创建和管理 FTP 连接的生命周期
     */
    public FtpClientPool(PooledObjectFactory<Ftp> factory) {
        super(factory);
    }

    /**
     * 使用自定义配置创建 FTP 客户端连接池.
     * <p>
     * 该构造方法允许传入自定义的 {@link GenericObjectPoolConfig} 配置对象，
     * 以精细化控制连接池的行为，包括：
     * <ul>
     *   <li>最大连接数（maxTotal）</li>
     *   <li>最大空闲连接数（maxIdle）</li>
     *   <li>最小空闲连接数（minIdle）</li>
     *   <li>连接最大等待时间（maxWaitMillis）</li>
     *   <li>借出时是否验证连接（testOnBorrow）</li>
     *   <li>归还时是否验证连接（testOnReturn）</li>
     *   <li>空闲时是否验证连接（testWhileIdle）</li>
     *   <li>逐出检查间隔（timeBetweenEvictionRunsMillis）</li>
     * </ul>
     * </p>
     *
     * @param factory FTP 客户端对象工厂，负责创建和管理 FTP 连接的生命周期
     * @param config  连接池的自定义配置对象
     */
    public FtpClientPool(PooledObjectFactory<Ftp> factory, GenericObjectPoolConfig config) {
        super(factory, config);
    }

}
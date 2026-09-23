package im.tlgxy.cloud.module.storage.support.webdav;

import com.github.sardine.impl.SardineImpl;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;

import java.io.IOException;
import java.time.Duration;

/**
 * 自定义 WebDAV 客户端实现 —— 扩展 Sardine 以支持自定义超时时间配置.
 * <p>
 * 该类继承自 Sardine（WebDAV 客户端库）的 {@link SardineImpl} 默认实现，
 * 在其基础上增加了对连接超时（connectTimeout）和读取超时（readTimeout）的支持。
 * </p>
 * <p>
 * <b>背景说明：</b>
 * Sardine 是 Java 生态中常用的 WebDAV 客户端库，封装了基于 HTTP 协议的 WebDAV 操作
 * （如文件上传、下载、目录列表、移动、复制等）。但其默认实现不支持精细化的超时配置，
 * 在连接慢速或不稳定的 WebDAV 服务器时，可能导致请求长时间挂起，影响用户体验。
 * </p>
 * <p>
 * <b>实现原理：</b>
 * 通过重写 {@link #execute(HttpClientContext, HttpRequestBase, ResponseHandler)} 方法，
 * 在每次 HTTP 请求执行前，修改底层 {@link RequestConfig} 的超时参数：
 * <ul>
 *   <li><b>connectTimeout（连接超时）</b>：建立 TCP 连接的最大等待时间，
 *       同时应用于 {@code ConnectTimeout} 和 {@code ConnectionRequestTimeout}</li>
 *   <li><b>readTimeout（读取超时 / Socket 超时）</b>：等待数据响应的最大时间，
 *       应用于 {@code SocketTimeout}</li>
 * </ul>
 * 超时值以 {@link Duration} 类型传入，内部转换为毫秒后设置到 Apache HttpClient 的请求配置中。
 * </p>
 *
 * @see SardineImpl Sardine 的默认实现类
 */
public class CustomSardine extends SardineImpl {

    /**
     * TCP 连接超时时间.
     * <p>
     * 包括建立 TCP 连接的最大等待时间，以及从连接池获取连接的最大等待时间。
     * 如果在此时间内无法建立连接，将抛出连接超时异常。
     * </p>
     */
    private final Duration connectTimeout;

    /**
     * 数据读取超时时间（Socket 超时）.
     * <p>
     * 指已经建立连接后，等待服务器返回数据的最大时间。
     * 如果在此时间内没有收到任何数据，将抛出读取超时异常。
     * 适用于防止因服务器处理缓慢或网络不稳定导致的长时间阻塞。
     * </p>
     */
    private final Duration readTimeout;

    /**
     * 构造函数，创建带有自定义超时配置的 WebDAV 客户端.
     * <p>
     * 调用父类 {@link SardineImpl} 的无参构造函数初始化基础 HTTP 客户端，
     * 然后设置 WebDAV 认证凭据和自定义的超时参数。
     * </p>
     *
     * @param username       WebDAV 服务器登录用户名
     * @param password       WebDAV 服务器登录密码
     * @param connectTimeout TCP 连接超时时间，可以为 null（表示不设置连接超时）
     * @param readTimeout    数据读取超时时间，可以为 null（表示不设置读取超时）
     */
    public CustomSardine(String username, String password, Duration connectTimeout, Duration readTimeout) {
        super();
        // 设置 WebDAV 的 Basic 认证凭据
        setCredentials(username, password);
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * 执行 HTTP 请求（重写父类方法以支持自定义超时设置）.
     * <p>
     * 该方法在 Sardine 的每次 HTTP 请求执行前被调用，是添加超时配置的切入点。
     * 处理流程：
     * <ol>
     *   <li>获取当前请求的 {@link RequestConfig} 配置（如果已有则复制，否则创建新配置）</li>
     *   <li>如果设置了 connectTimeout（非 null 且大于零），则设置连接超时和连接请求超时</li>
     *   <li>如果设置了 readTimeout（非 null 且大于零），则设置 Socket 读取超时</li>
     *   <li>将修改后的配置应用到请求对象上</li>
     *   <li>调用父类的 execute 方法继续执行请求</li>
     * </ol>
     * </p>
     * <p>
     * <b>超时值转换说明：</b>
     * 将 {@link Duration} 转换为毫秒时，采用公式：
     * {@code 1000 * seconds + nanos / 1,000,000}，
     * 确保纳秒部分也被精确转换为毫秒，避免精度丢失。
     * </p>
     *
     * @param context         HTTP 客户端上下文，包含 Cookie 存储、认证状态等信息
     * @param request         要执行的 HTTP 请求对象（如 GET、PUT、PROPFIND 等）
     * @param responseHandler 响应处理器，用于将 HTTP 响应转换为目标类型
     * @param <T>             响应处理器的返回类型
     * @return 经过响应处理器处理后的结果
     * @throws IOException 如果执行 HTTP 请求或处理响应时发生 I/O 错误
     */
    @Override
    protected <T> T execute(HttpClientContext context, HttpRequestBase request, ResponseHandler<T> responseHandler) throws IOException {
        // 获取当前请求的配置（如果已有则复制，否则创建默认配置）
        RequestConfig.Builder configBuilder = request.getConfig() != null
                ? RequestConfig.copy(request.getConfig())
                : RequestConfig.custom();

        // 设置连接超时：同时设置 ConnectTimeout 和 ConnectionRequestTimeout
        if (connectTimeout != null && connectTimeout.compareTo(Duration.ZERO) > 0) {
            // 将 Duration 转换为毫秒：秒部分 * 1000 + 纳秒部分 / 1,000,000
            int connectTimeoutMillis = (int) (1000 * connectTimeout.getSeconds() + connectTimeout.getNano() / 1000000);
            configBuilder.setConnectTimeout(connectTimeoutMillis);
            configBuilder.setConnectionRequestTimeout(connectTimeoutMillis);
        }

        // 设置 Socket 读取超时
        if (readTimeout != null && readTimeout.compareTo(Duration.ZERO) > 0) {
            // 将 Duration 转换为毫秒
            int readTimeoutMillis = (int) (1000 * readTimeout.getSeconds() + readTimeout.getNano() / 1000000);
            configBuilder.setSocketTimeout(readTimeoutMillis);
        }

        // 将修改后的请求配置应用到当前请求
        request.setConfig(configBuilder.build());
        // 调用父类方法继续执行 HTTP 请求
        return super.execute(context, request, responseHandler);
    }

}
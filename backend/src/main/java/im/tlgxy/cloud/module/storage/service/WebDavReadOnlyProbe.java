package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperEndpointPolicy;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import jakarta.annotation.PreDestroy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** HTTPS Basic-auth/anonymous PROPFIND Depth:0 only, with public-IP pinning and no redirects or writes. */
@Component
public class WebDavReadOnlyProbe {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final S3HelperEndpointPolicy policy;
    private final ProbeTransport transport;

    @Autowired public WebDavReadOnlyProbe(S3HelperEndpointPolicy policy) { this(policy, new ApacheProbeTransport()); }
    WebDavReadOnlyProbe(S3HelperEndpointPolicy policy, ProbeTransport transport) { this.policy = policy; this.transport = transport; }

    public void assertReadable(StorageSourceAllParamDTO parameters) {
        URI directory = directory(parameters.getUrl(), parameters.getBasePath());
        String username = parameters.getUsername();
        String password = parameters.getPassword();
        if (username != null && (username.length() > 512 || username.contains(":") || username.matches("(?s).*\\p{Cntrl}.*"))
                || password != null && (password.length() > 4096 || password.matches("(?s).*\\p{Cntrl}.*")))
            throw new BizException("WebDAV 用户名或密码格式不正确");
        if ((username == null || username.isBlank()) && password != null && !password.isBlank())
            throw new BizException("WebDAV 密码需要同时填写用户名");
        try {
            // Validate origin separately: paths are checked above and the resolved public IPs are pinned in transport.
            URI origin = new URI(directory.getScheme(), null, directory.getHost(), directory.getPort(), null, null, null);
            var endpoint = policy.validate(origin.toString());
            Response response = transport.propfind(endpoint, directory, username, password);
            if (response == null || response.status() != 207 || response.body() == null || response.body().length > MAX_RESPONSE_BYTES)
                throw new BizException("WebDAV 只读测试未通过；需要 HTTPS Basic/无认证及 PROPFIND 目录读取权限，不能跟随跳转");
            requireCollection(response.body());
        } catch (BizException error) { throw error; }
        catch (Exception error) { throw new BizException("WebDAV 只读连接测试失败；请检查公网 HTTPS 地址、证书、凭据与目录权限，未保存配置"); }
    }

    static URI directory(String suppliedUrl, String basePath) {
        try {
            if (suppliedUrl == null || suppliedUrl.isBlank() || suppliedUrl.length() > 2048) throw new IllegalArgumentException();
            URI supplied = URI.create(suppliedUrl.trim());
            if (!"https".equalsIgnoreCase(supplied.getScheme()) || supplied.getHost() == null
                    || supplied.getRawUserInfo() != null || supplied.getRawQuery() != null || supplied.getRawFragment() != null)
                throw new IllegalArgumentException();
            String root = supplied.getRawPath() == null || supplied.getRawPath().isBlank() ? "/" : supplied.getRawPath();
            String path = basePath == null || basePath.isBlank() ? "/" : basePath;
            for (String value : new String[]{root, path}) {
                if (value.length() > 2048 || !value.startsWith("/") || value.contains("\\") || value.contains("%")
                        || value.matches("(?s).*\\p{Cntrl}.*") || Arrays.asList(value.split("/")).contains("..")
                        || Arrays.asList(value.split("/")).contains(".")) throw new IllegalArgumentException();
            }
            String combined = root.replaceAll("/+$", "") + "/" + path.replaceFirst("^/+", "");
            return new URI("https", null, supplied.getHost(), supplied.getPort(), combined, null, null);
        } catch (Exception error) {
            throw new BizException("WebDAV 测试需要不含 URL 凭据、查询、跳转或路径回退的公网 HTTPS 地址");
        }
    }

    static void requireCollection(byte[] xml) {
        try {
            if (xml.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            var document = builder.parse(new ByteArrayInputStream(xml));
            if (!"DAV:".equals(document.getDocumentElement().getNamespaceURI())
                    || !"multistatus".equals(document.getDocumentElement().getLocalName())) throw new IllegalArgumentException();
            var properties = document.getElementsByTagNameNS("DAV:", "propstat");
            for (int index = 0; index < properties.getLength(); index++) {
                var property = (org.w3c.dom.Element) properties.item(index);
                var statuses = property.getElementsByTagNameNS("DAV:", "status");
                if (statuses.getLength() == 1 && statuses.item(0).getTextContent().matches("HTTP/[0-9.]+ 200(?: .*)?")
                        && property.getElementsByTagNameNS("DAV:", "collection").getLength() == 1) return;
            }
            throw new IllegalArgumentException();
        } catch (Exception error) { throw new BizException("WebDAV 未返回可读取的目录元数据；未保存配置或写入文件"); }
    }

    @PreDestroy public void close() {
        if (transport instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception ignored) { }
    }
    record Response(int status, byte[] body) { }
    @FunctionalInterface interface ProbeTransport {
        Response propfind(S3HelperEndpointPolicy.ValidatedEndpoint endpoint, URI directory, String username, String password) throws Exception;
    }

    private static final class ApacheProbeTransport implements ProbeTransport, AutoCloseable {
        private final ScheduledThreadPoolExecutor timeouts;
        ApacheProbeTransport() {
            timeouts = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "webdav-preflight-timeout"); thread.setDaemon(true); return thread;
            });
            timeouts.setRemoveOnCancelPolicy(true);
        }
        @Override public Response propfind(S3HelperEndpointPolicy.ValidatedEndpoint endpoint, URI directory,
                                            String username, String password) throws Exception {
            var registry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("https", SSLConnectionSocketFactory.getSocketFactory()).build();
            var manager = new PoolingHttpClientConnectionManager(registry, endpoint.pinnedResolver());
            manager.setMaxTotal(1); manager.setDefaultMaxPerRoute(1);
            manager.setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(3000).build());
            var request = new HttpEntityEnclosingRequestBase() { @Override public String getMethod() { return "PROPFIND"; } };
            request.setURI(directory);
            request.setHeader("Depth", "0");
            request.setHeader("Accept", "application/xml");
            request.setEntity(new StringEntity("<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>", StandardCharsets.UTF_8));
            request.setHeader("Content-Type", "application/xml; charset=UTF-8");
            if (username != null && !username.isBlank()) request.setHeader("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8)));
            var timeout = timeouts.schedule(request::abort, 10, TimeUnit.SECONDS);
            try (var client = HttpClients.custom().setConnectionManager(manager)
                    .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                    .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(3000).setSocketTimeout(3000)
                            .setConnectionRequestTimeout(1000).setAuthenticationEnabled(false).build()).build();
                 var response = client.execute(request)) {
                byte[] body = response.getEntity() == null ? new byte[0]
                        : response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
                return new Response(response.getStatusLine().getStatusCode(), body);
            } finally { timeout.cancel(false); manager.close(); }
        }
        @Override public void close() { timeouts.shutdownNow(); }
    }
}

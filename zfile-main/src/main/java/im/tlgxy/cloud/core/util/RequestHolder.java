package im.tlgxy.cloud.core.util;

import cn.hutool.extra.servlet.JakartaServletUtil;
import im.tlgxy.cloud.core.constant.TlgxyCloudHttpHeaderConstant;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 请求上下文工具类
 * <p>
 * 提供获取当前请求上下文中的 {@link HttpServletRequest} 和 {@link HttpServletResponse} 的便捷方法，
 * 以及向响应流写入文件、判断请求来源、获取服务器地址等常用操作。
 * 基于 Spring 的 {@link RequestContextHolder} 获取当前线程绑定的请求上下文。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class RequestHolder {

    /**
     * 获取当前请求的 HttpServletRequest 对象
     * <p>
     * 从 Spring 的 {@link RequestContextHolder} 中获取当前线程绑定的请求属性，
     * 如果当前不在请求上下文中（如非 Web 请求的线程），会抛出 {@link NullPointerException}。
     * </p>
     *
     * @return 当前请求的 HttpServletRequest 实例
     */
    public static HttpServletRequest getRequest() {
        return ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getRequest();
    }


    /**
     * 获取当前请求的 HttpServletResponse 对象
     * <p>
     * 从 Spring 的 {@link RequestContextHolder} 中获取当前线程绑定的响应对象。
     * 如果当前不在请求上下文中，会抛出 {@link NullPointerException}。
     * </p>
     *
     * @return 当前请求的 HttpServletResponse 实例
     */
    public static HttpServletResponse getResponse() {
        return ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getResponse();
    }


    /**
     * 向 HTTP 响应流写入文件内容
     * <p>
     * 支持以下功能：
     * <ul>
     *   <li><b>强制下载 / 内联预览</b>：通过 Content-Disposition 头控制浏览器行为。设置为 attachment 时浏览器弹出下载对话框，inline 时尝试在浏览器内预览。</li>
     *   <li><b>Range 断点续传</b>：支持 HTTP Range 请求头，允许客户端分片下载大文件。如果客户端未指定 Range，则返回整个文件。</li>
     *   <li><b>MIME 类型识别</b>：根据文件名自动识别 Content-Type，无法识别时使用 application/octet-stream。</li>
     *   <li><b>异常处理</b>：对 Broken pipe 和 Connection reset by peer 等客户端断开连接的异常进行静默处理，避免不必要的错误日志。</li>
     * </ul>
     * </p>
     *
     * @param   inputStream
     *          文件输入流，不能为 null（为 null 时抛出 {@link BizException}）
     *
     * @param   fileName
     *          文件名称，用于设置 Content-Disposition 响应头和识别 MIME 类型
     *
     * @param   fileSize
     *          文件总大小（字节），用于设置 Content-Length 响应头和计算 Range 范围。
     *          如果为 null 或小于等于 0，则不启用 Range 支持，直接将输入流完整复制到输出流。
     *
     * @param   isPartialContentFromInputStream
     *          表示输入流是否已经是部分内容。
     *          当该参数为 true 时，意味着调用方已经根据 Range 请求从存储源获取了部分内容，
     *          此时不需要再通过 {@link StreamUtils#copyRange} 做游标跳转，
     *          而是直接将输入流的全部内容复制到输出流。
     *          当该参数为 false 时，会使用 {@link StreamUtils#copyRange} 从输入流的指定位置开始读取。
     *
     * @param   forceDownload
     *          是否强制下载。true 时 Content-Disposition 为 attachment（浏览器弹出下载），
     *          false 时为 inline（浏览器尝试预览）。
     */
    public static void writeFile(InputStream inputStream, String fileName, Long fileSize, boolean isPartialContentFromInputStream, boolean forceDownload) {
        // 输入流为空，说明文件不存在，抛出业务异常
        if (inputStream == null) {
            throw new BizException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }
        OutputStream outputStream = null;
        // 使用 try-with-resources 确保输入流被正确关闭
        try (InputStream innerInputStream = inputStream) {
            HttpServletResponse response = RequestHolder.getResponse();

            // 根据 forceDownload 参数设置 Content-Disposition：attachment 为强制下载，inline 为内联预览
            ContentDisposition contentDisposition = ContentDisposition
                    .builder(forceDownload ? "attachment" : "inline")
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build();
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());

            // 设置 Content-Type：强制下载时使用二进制流类型，否则根据文件名自动识别
            if (forceDownload) {
                response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            } else {
                response.setContentType(MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM).toString());
            }

            outputStream = response.getOutputStream();

            // 如果文件大小有效，则处理 Range 请求以支持断点续传
            if (fileSize != null && fileSize > 0) {
                String range = RequestHolder.getRequest().getHeader(HttpHeaders.RANGE);
                List<HttpRange> httpRanges = HttpRange.parseRanges(range);
                // 如果客户端没有指定 Range，则默认返回整个文件范围
                if (httpRanges.isEmpty()) {
                    httpRanges = Collections.singletonList(HttpRange.createByteRange(0, fileSize - 1));
                } else {
                    // 客户端指定了 Range，设置 206 Partial Content 状态码
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                }
                // 取第一个 Range（目前仅支持单段 Range）
                HttpRange httpRange = CollectionUtils.getFirst(httpRanges);
                long startPos = httpRange.getRangeStart(fileSize);
                long endPos = httpRange.getRangeEnd(fileSize);

                // 如果是部分内容响应，设置 Content-Range 响应头，格式：bytes start-end/total
                if (response.getStatus() == HttpServletResponse.SC_PARTIAL_CONTENT) {
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + startPos + "-" + endPos + StringUtils.SLASH + fileSize);
                }

                // 设置 Accept-Ranges 头告知客户端支持断点续传
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                // 设置实际返回的内容长度
                response.setContentLengthLong(endPos - startPos + 1);

                if (isPartialContentFromInputStream) {
                    // 输入流已经是部分内容，直接完整复制
                    StreamUtils.copy(innerInputStream, outputStream);
                } else {
                    // 输入流是完整文件内容，需要根据 Range 跳过 startPos 之前的内容，只复制指定范围
                    StreamUtils.copyRange(innerInputStream, outputStream, startPos, endPos);
                }
                return;
            }

            // 文件大小无效（为 null 或小于等于 0），直接将整个输入流复制到输出流
            StreamUtils.copy(innerInputStream, outputStream);
        } catch (IOException e) {
            // 对客户端断开连接的常见异常进行静默处理，避免日志刷屏
            boolean isBrokenPipe = e.getMessage().contains("Broken pipe");
            boolean isConnectionResetByPeer = e.getMessage().contains("Connection reset by peer");
            if (isBrokenPipe || isConnectionResetByPeer) {
                if (log.isDebugEnabled()) {
                    log.debug("skip IOException: {}", e.getMessage());
                }
            } else {
                // 其他 IO 异常包装为系统异常抛出
                throw new SystemException(e);
            }
        } finally {
            // 确保输入流和输出流在 finally 块中被关闭，防止资源泄漏
            IOUtils.closeQuietly(inputStream);
            IOUtils.closeQuietly(outputStream);
        }
    }

    /**
     * 判断当前请求是否来自 Axios（前端 HTTP 库）
     * <p>
     * 通过检查请求头中是否存在自定义的 Axios 标记头来判断请求来源。
     * 前端在发起请求时会携带一个自定义请求头（定义在 {@link TlgxyCloudHttpHeaderConstant#AXIOS_REQUEST}），
     * 服务端通过检查该头是否存在来判断是否为 Axios 发起的请求。
     * </p>
     *
     * @return 如果是 Axios 请求返回 true，否则返回 false
     */
    public static boolean isAxiosRequest() {
        HttpServletRequest request = RequestHolder.getRequest();
        String axiosRequest = JakartaServletUtil.getHeaderIgnoreCase(request, TlgxyCloudHttpHeaderConstant.AXIOS_REQUEST);
        return StringUtils.isNotEmpty(axiosRequest);
    }


    /**
     * 获取请求头中的 Axios-From 字段
     * <p>
     * 用于获取前端 Axios 请求中携带的来源标识（定义在 {@link TlgxyCloudHttpHeaderConstant#AXIOS_FROM}），
     * 可用于区分不同的前端页面或功能模块发起的请求。
     * 如果当前不在 Web 请求上下文中（如定时任务线程），则返回 null。
     * </p>
     *
     * @return Axios-From 请求头的值，如果不存在或无请求上下文则返回 null
     */
    public static String getAxiosFrom() {
        // 如果当前不在请求上下文中（如后台线程），直接返回 null，避免空指针异常
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        HttpServletRequest request = RequestHolder.getRequest();
        return JakartaServletUtil.getHeaderIgnoreCase(request, TlgxyCloudHttpHeaderConstant.AXIOS_FROM);
    }

    /**
     * 获取后端服务的完整访问地址（协议 + 域名 + 端口）
     * <p>
     * 该方法用于构造后端服务的可访问 URL 地址，支持反向代理场景。
     * 处理逻辑如下：
     * <ol>
     *   <li>优先从反向代理设置的 <code>X-Forwarded-Host</code>、<code>X-Forwarded-Port</code>、
     *       <code>X-Forwarded-Proto</code> 请求头中获取真实访问信息。</li>
     *   <li>如果没有反向代理头，则从 <code>Host</code> 请求头和 {@link HttpServletRequest#getServerName()} 中获取。</li>
     *   <li>自动去除默认端口（HTTP 的 80 端口和 HTTPS 的 443 端口），使 URL 更简洁。</li>
     * </ol>
     * 如果当前不在 Web 请求上下文中，则返回 null。
     * </p>
     *
     * @return 后端服务地址，格式如 <code>http://example.com</code> 或 <code>https://example.com:8443</code>，
     *         如果没有请求上下文则返回 null
     */
    public static String getRequestServerAddress() {
        // 如果当前不在请求上下文中，返回 null
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        HttpServletRequest request = RequestHolder.getRequest();

        // 获取反向代理转发头部信息
        String forwardedHost = JakartaServletUtil.getHeaderIgnoreCase(request, "X-Forwarded-Host");
        String forwardedPort = JakartaServletUtil.getHeaderIgnoreCase(request, "X-Forwarded-Port");
        String forwardedProto = JakartaServletUtil.getHeaderIgnoreCase(request, "X-Forwarded-Proto");

        // 协议：优先使用反向代理转发的协议，其次使用请求本身的协议
        String scheme = StringUtils.isBlank(forwardedProto) ? request.getScheme() : forwardedProto;

        // 服务器名称：优先使用 X-Forwarded-Host，其次使用 Host 请求头，最后使用 request.getServerName()
        String serverName;
        String hostHeader = StringUtils.isNotBlank(forwardedHost) ? forwardedHost : request.getHeader("Host");
        if (StringUtils.isNotBlank(hostHeader)) {
            // Host 头可能包含端口信息，如 "example.com:8080"，需要拆分处理
            String[] hostParts = hostHeader.split(":");
            serverName = hostParts[0];
            // 如果 Host 头包含端口且没有显式设置 X-Forwarded-Port，则使用 Host 头中的端口
            if (hostParts.length > 1 && StringUtils.isBlank(forwardedPort)) {
                forwardedPort = hostParts[1];
            }
        } else {
            serverName = request.getServerName();
        }

        // 端口处理逻辑：按优先级使用转发端口 -> 转发协议的默认端口 -> 请求的实际端口
        String port;
        if (StringUtils.isNotBlank(forwardedPort)) {
            port = forwardedPort;
        } else if (StringUtils.isNotBlank(forwardedProto)) {
            // 如果设置了转发协议但没有设置端口，使用协议的默认端口
            port = "https".equalsIgnoreCase(forwardedProto) ? "443" : "80";
        } else {
            port = String.valueOf(request.getServerPort());
        }

        // 移除协议的默认端口，使 URL 更加简洁美观
        if ("443".equals(port) && "https".equalsIgnoreCase(scheme)) {
            port = "";
        }
        if ("80".equals(port) && "http".equalsIgnoreCase(scheme)) {
            port = "";
        }

        // 拼接最终的服务地址
        if (StringUtils.isBlank(port)) {
            return scheme + "://" + serverName;
        } else {
            return scheme + "://" + serverName + ":" + port;
        }
    }


    /**
     * 获取当前请求的 Origin 请求头
     * <p>
     * Origin 请求头表示请求的来源（协议 + 域名 + 端口），
     * 通常用于 CORS 跨域校验。如果当前不在 Web 请求上下文中，则返回 null。
     * </p>
     *
     * @return  Origin 请求头的值，格式如 <code>http://example.com</code>，
     *          如果不存在或无请求上下文则返回 null
     */
    public static String getOriginAddress() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        HttpServletRequest request = RequestHolder.getRequest();
        return JakartaServletUtil.getHeaderIgnoreCase(request, HttpHeaders.ORIGIN);
    }


}
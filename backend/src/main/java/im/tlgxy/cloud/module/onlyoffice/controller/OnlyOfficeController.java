package im.tlgxy.cloud.module.onlyoffice.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.alibaba.fastjson2.JSONObject;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.*;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeCallback;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeFile;
import im.tlgxy.cloud.module.onlyoffice.service.OnlyOfficeConfigService;
import im.tlgxy.cloud.module.storage.annotation.CheckPassword;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileItemRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.beans.Beans;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OnlyOffice 在线办公控制器
 * <p>
 * 负责与 OnlyOffice Document Server 交互，提供文件在线预览和编辑功能。
 * 主要包括两个核心接口：
 * <ul>
 *   <li>预览配置生成：根据文件信息生成 OnlyOffice 所需的 JSON 配置，返回给前端加载编辑器</li>
 *   <li>保存回调处理：接收 OnlyOffice 的编辑保存回调，将编辑后的文件回写到存储源</li>
 * </ul>
 * 在回调处理中包含了完整的 SSRF 防护（校验回调 URL 与配置的 Document Server 同源）、
 * JWT 签名验证、用户权限再校验等安全措施。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Tag(name = "OnlyOffice 相关接口")
@RestController
@RequestMapping("/onlyOffice")
public class OnlyOfficeController {

    /** 系统配置服务，用于获取 OnlyOffice 的 Secret 和 URL 等配置 */
    @Resource
    private SystemConfigService systemConfigService;

    /** 存储源服务，用于根据存储源 key 查找存储源 ID */
    @Resource
    private StorageSourceService storageSourceService;

    /** 用户存储源权限服务，用于校验用户的上传/编辑权限 */
    @Resource
    private UserStorageSourceService userStorageSourceService;

    /** OnlyOffice 配置生成服务，负责构建预览所需的 JSON 配置 */
    @Resource
    private OnlyOfficeConfigService onlyOfficeConfigService;

    /** 回调返回的错误消息（JSON 格式，表示处理失败） */
    private static final String CALLBACK_ERROR_MSG = "{\"error\":1}";

    /** 回调返回的成功消息（JSON 格式，表示处理成功） */
    private static final String CALLBACK_SUCCESS_MSG = "{\"error\":0}";

    /**
     * OnlyOffice 回调所有需要处理的状态(保存 + 错误), 用于清理 key 缓存.
     * 状态值含义：2-文档已准备好保存，3-文档保存错误，6-正在编辑但已保存当前状态，7-强制保存时出错
     */
    public static final List<Integer> SUPPORTED_STATUS = List.of(2, 3, 6, 7);

    /**
     * OnlyOffice 回调真正需要保存写入存储的状态. 错误状态(3, 7)只清缓存, 不写文件.
     */
    private static final List<Integer> SAVE_STATUS = List.of(2, 6);

    /** OnlyOffice 下载文件的连接超时时间（毫秒） */
    private static final int ONLY_OFFICE_DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 5_000;

    /** OnlyOffice 下载文件的读取超时时间（毫秒） */
    private static final int ONLY_OFFICE_DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000;

    /** OnlyOffice 下载文件的最大重定向次数（超过则拒绝，防止 SSRF 利用重定向绕过后台检测） */
    private static final int ONLY_OFFICE_DOWNLOAD_MAX_REDIRECTS = 5;

    /**
     * 生成 OnlyOffice 预览配置
     * <p>
     * 根据传入的文件信息，生成 OnlyOffice 编辑器所需的 JSON 配置数据。
     * 同时将预览上下文（用户 ID、是否允许编辑等）缓存起来，供后续回调时进行权限校验。
     * </p>
     *
     * @param fileItemRequest 文件请求参数，包含存储源 key、文件路径和密码
     * @return 包含 OnlyOffice 预览配置 JSON 的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 3)
    @Operation(summary = "OnlyOffice 预览文件", description = "根据传入的文件信息, 生成 OnlyOffice 预览所需的 JSON 数据.")
    @PostMapping("/config/token")
    @CheckPassword(storageKeyFieldExpression = "[0].storageKey",
            pathFieldExpression = "[0].path",
            pathIsDirectory = false,
            passwordFieldExpression = "[0].password")
    public AjaxJson<JSONObject> getPreviewFileJSONInfo(@Valid @RequestBody FileItemRequest fileItemRequest) {
        String storageKey = fileItemRequest.getStorageKey();
        // 根据存储源 key 查找存储源 ID，验证存储源是否存在
        Integer storageId = storageSourceService.findIdByKey(storageKey);
        if (storageId == null) {
            throw new InvalidStorageSourceBizException(storageKey);
        }

        // 处理请求参数的默认值（如兼容旧版参数名等）
        fileItemRequest.handleDefaultValue();

        // 获取对应存储源的文件服务实现
        AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageId(storageId);
        // 原始(用户视角)路径, 不含用户根目录, 回调时用它直接调 uploadFile, 避免重复拼接.
        String originalPath = fileItemRequest.getPath();

        try {
            // 获取文件信息（大小、URL 等）
            FileItemResult fileInfo = fileService.getFileItem(originalPath);
            if (fileInfo == null) {
                throw new BizException("文件不存在");
            }

            // 拼接出完整路径作为缓存键, 让不同用户根目录下的同名文件互不冲突,
            // 同时同一物理文件的多人预览能命中同一个 OnlyOffice 协同编辑 key.
            String fullPath = StringUtils.concat(fileService.getCurrentUserBasePath(), originalPath);

            // 检查当前用户是否有上传权限，决定预览时是否允许编辑
            boolean hasUploadPermission = userStorageSourceService.hasCurrentUserStorageOperatorPermission(storageId, FileOperatorTypeEnum.UPLOAD);

            Integer currentUserId = TlgxyCloudAuthUtil.getCurrentUserId();
            // 把发起预览的用户 ID 一并写入缓存, 回调时据此校验权限, 避免信任回调中的 users 字段.
            OnlyOfficeFile onlyOfficeFile = new OnlyOfficeFile(storageKey, fullPath, originalPath, currentUserId, false);

            // 生成 OnlyOffice 预览所需的 JSON 配置
            JSONObject onlyOfficePayload = onlyOfficeConfigService.createConfig(fileInfo, onlyOfficeFile, hasUploadPermission);
            return AjaxJson.getSuccessData(onlyOfficePayload);
        } catch (Exception e) {
            throw new BizException("获取文件信息失败: " + e.getMessage());
        }
    }


    /**
     * OnlyOffice 保存回调接口
     * <p>
     * 接收 OnlyOffice Document Server 的回调请求，处理文档编辑后的保存操作。
     * 包含以下安全措施：
     * <ul>
     *   <li>JWT Token 验签：确保回调来自合法的 OnlyOffice 服务端</li>
     *   <li>Token 与 Key 匹配校验：防止用其他文档的 Token 越权写入</li>
     *   <li>SSRF 防护：严格校验下载地址与配置的 Document Server 同源</li>
     *   <li>用户权限再校验：使用预览时缓存的用户身份重新校验上传权限</li>
     *   <li>编辑权限校验：拒绝预览时不允许编辑的请求</li>
     * </ul>
     * </p>
     *
     * @param onlyOfficeCallback OnlyOffice 回调请求体，包含文档状态、下载地址、JWT Token 等信息
     * @return JSON 格式的处理结果，成功返回 {"error":0}，失败返回 {"error":1}
     */
    @RequestMapping("/callback")
    public String callBack(@RequestBody OnlyOfficeCallback onlyOfficeCallback) {
        if (log.isDebugEnabled()) {
            log.debug("OnlyOffice 回调信息: {}, {}", onlyOfficeCallback.getStatus(), onlyOfficeCallback);
        }

        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        String onlyOfficeSecret = systemConfig.getOnlyOfficeSecret();
        // 未配置 secret 时, callback 无法验签, 直接拒绝, 避免被未授权请求触发存储写入.
        if (StrUtil.isBlank(onlyOfficeSecret)) {
            log.warn("OnlyOffice 回调被拒绝: 未配置 Secret, key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return CALLBACK_ERROR_MSG;
        }

        // Token 为空时无法验证请求合法性，直接拒绝
        if (StrUtil.isBlank(onlyOfficeCallback.getToken())) {
            log.error("OnlyOffice 回调 Token 为空: key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return CALLBACK_ERROR_MSG;
        }

        // 使用配置的 Secret 验证 JWT Token 的签名
        if (!JWTUtil.verify(onlyOfficeCallback.getToken(), StrUtil.bytes(onlyOfficeSecret, StandardCharsets.UTF_8))) {
            log.error("OnlyOffice 回调 Token 验证失败: key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return CALLBACK_ERROR_MSG;
        }

        // 仅在真正需要保存写入存储时再执行 URL 下载与上传; 错误状态(3, 7)仍然清掉 key 缓存.
        if (!SUPPORTED_STATUS.contains(onlyOfficeCallback.getStatus())) {
            return CALLBACK_SUCCESS_MSG;
        }

        // 从缓存中获取预览时保存的文件信息
        String key = onlyOfficeCallback.getKey();
        OnlyOfficeFile onlyOfficeFile = OnlyOfficeKeyCacheUtils.removeByKey(key);
        // 文件不存在或者存储策略不存在, 直接返回错误信息.
        if (onlyOfficeFile == null) {
            return CALLBACK_ERROR_MSG;
        }

        // 对该文件的回调处理加锁，防止并发写入冲突
        ReentrantLock lock = OnlyOfficeKeyCacheUtils.getLock(onlyOfficeFile);
        lock.lock();
        if (log.isDebugEnabled()) {
            log.debug("开始处理 OnlyOffice 文件: {}, 加锁", key);
        }
        try {
            // 错误状态仅清缓存, 不写入存储.
            if (!SAVE_STATUS.contains(onlyOfficeCallback.getStatus())) {
                return CALLBACK_SUCCESS_MSG;
            }

            // 校验回调 body 中的 key 是否与 token payload 中的 key 一致, 防止用其他文档的 token 写本文档.
            if (!isCallbackTokenKeyMatched(onlyOfficeCallback)) {
                log.warn("OnlyOffice 回调 Token 与 key 不匹配: key={}, status={}",
                        key, onlyOfficeCallback.getStatus());
                return CALLBACK_ERROR_MSG;
            }

            // 预览发放时不允许编辑, 即使签名有效也拒绝写入.
            if (!onlyOfficeFile.isAllowEdit()) {
                log.warn("OnlyOffice 回调保存被拒绝: 预览配置不允许编辑, key={}, storageKey={}, path={}",
                        key, onlyOfficeFile.getStorageKey(), onlyOfficeFile.getPathAndName());
                return CALLBACK_ERROR_MSG;
            }

            // 根据存储源 key 获取存储源的文件服务实现
            AbstractBaseFileService<?> storageServiceByKey = StorageSourceContext.getByStorageKey(onlyOfficeFile.getStorageKey());
            if (storageServiceByKey == null) {
                return CALLBACK_ERROR_MSG;
            }

            Integer cachedUserId = onlyOfficeFile.getUserId();
            Integer storageId = storageSourceService.findIdByKey(onlyOfficeFile.getStorageKey());
            // 使用预览时缓存的用户身份重新校验权限, 而非信任 callback 中的 users.
            if (storageId == null
                    || !userStorageSourceService.hasUserStorageOperatorPermission(cachedUserId, storageId, FileOperatorTypeEnum.UPLOAD)) {
                log.warn("OnlyOffice 回调保存被拒绝: 缓存用户无上传权限, key={}, storageId={}, userId={}",
                        key, storageId, cachedUserId);
                return CALLBACK_ERROR_MSG;
            }

            // 切换为预览时记录的用户身份, 让下游 service 的 getCurrentUserBasePath / 权限判断生效.
            if (cachedUserId != null) {
                StpUtil.login(cachedUserId);
            }

            if (log.isDebugEnabled()) {
                log.debug("开始保存 OnlyOffice 文件: {}, {}", onlyOfficeFile.getStorageKey(), onlyOfficeFile.getPathAndName());
            }

            // 如果存储源支持代理上传，则从 OnlyOffice 下载编辑后的文件并回写到存储源
            if (Beans.isInstanceOf(storageServiceByKey, AbstractProxyTransferService.class)) {
                AbstractProxyTransferService<?> proxyUploadService = (AbstractProxyTransferService<?>) storageServiceByKey;
                try {
                    // 打开到 OnlyOffice 下载地址的连接（含 SSRF 校验和重定向处理）
                    URLConnection connection = openOnlyOfficeDownloadConnection(onlyOfficeCallback.getUrl(), systemConfig.getOnlyOfficeUrl());
                    long contentLength = connection.getContentLengthLong();
                    try (InputStream inputStream = connection.getInputStream()) {
                        // 使用原始相对路径(不含用户根目录), 由 uploadFile 内部按当前登录身份重新拼接 getCurrentUserBasePath,
                        // 避免缓存中的完整路径再叠加一次根目录, 把文件写到 /根目录/根目录/文件名 这样的错误位置.
                        String originalPath = onlyOfficeFile.getOriginalPath();
                        proxyUploadService.uploadFile(originalPath, inputStream, contentLength);
                    }
                } catch (IOException | URISyntaxException e) {
                    log.warn("OnlyOffice 回调下载地址拒绝或访问失败: key={}, urlHost={}, reason={}",
                            key, safeHost(onlyOfficeCallback.getUrl()), e.getMessage());
                    return CALLBACK_ERROR_MSG;
                } catch (Exception e) {
                    log.error("回调保存 OnlyOffice 文件失败: key={}, storageKey={}",
                            key, onlyOfficeFile.getStorageKey(), e);
                    return CALLBACK_ERROR_MSG;
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("完成保存 OnlyOffice 文件: {}, {}", onlyOfficeFile.getStorageKey(), onlyOfficeFile.getPathAndName());
            }
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("完成处理 OnlyOffice 文件: {}, 解锁", key);
            }
            // 确保释放锁
            lock.unlock();
        }
        return CALLBACK_SUCCESS_MSG;
    }

    /**
     * 校验 callback body 中的 key 与 JWT payload 中的 key 一致.
     * 防止: 攻击者拿到文档 A 的 token, 在 body 中改 key 指向文档 B.
     *
     * @param onlyOfficeCallback OnlyOffice 回调请求体
     * @return key 匹配返回 true，否则返回 false
     */
    private boolean isCallbackTokenKeyMatched(OnlyOfficeCallback onlyOfficeCallback) {
        try {
            // 解析 JWT Token
            JWT jwt = JWTUtil.parseToken(onlyOfficeCallback.getToken());
            // 尝试从 payload 顶层获取 key
            Object callbackKey = jwt.getPayload("key");
            if (callbackKey != null && Objects.equals(onlyOfficeCallback.getKey(), callbackKey.toString())) {
                return true;
            }

            // 预览生成时, key 嵌套在 document.key 中.
            Object document = jwt.getPayload("document");
            if (document instanceof java.util.Map) {
                Object documentKey = ((java.util.Map<?, ?>) document).get("key");
                if (documentKey != null && Objects.equals(onlyOfficeCallback.getKey(), documentKey.toString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("OnlyOffice 回调 Token 解析失败: key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return false;
        }
    }

    /**
     * 严格校验 OnlyOffice 回调中的下载地址必须与系统配置中的 OnlyOffice Document Server 同源,
     * 同时限制重定向次数与超时, 避免 SSRF 触达内网或任意域名.
     *
     * @param callbackUrl   OnlyOffice 回调中的文档下载地址
     * @param onlyOfficeUrl 系统配置的 OnlyOffice Document Server 地址
     * @return 打开后的 URLConnection 对象
     * @throws IOException        连接或 URL 校验失败时抛出
     * @throws URISyntaxException URL 格式错误时抛出
     */
    private URLConnection openOnlyOfficeDownloadConnection(String callbackUrl, String onlyOfficeUrl) throws IOException, URISyntaxException {
        URI currentUri = parseHttpUri(callbackUrl);
        URI onlyOfficeUri = parseHttpUri(onlyOfficeUrl);

        // 手动处理重定向，每一跳都校验目标地址与 OnlyOffice 服务器同源
        for (int i = 0; i <= ONLY_OFFICE_DOWNLOAD_MAX_REDIRECTS; i++) {
            // 校验当前 URL 与 OnlyOffice Document Server 是否同源（scheme、host、port 一致）
            validateOnlyOfficeUrl(currentUri, onlyOfficeUri);
            URLConnection connection = currentUri.toURL().openConnection();
            connection.setConnectTimeout(ONLY_OFFICE_DOWNLOAD_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(ONLY_OFFICE_DOWNLOAD_READ_TIMEOUT_MILLIS);

            // 对于 HTTP 连接，关闭自动重定向，手动校验每一跳目标
            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(false);
                int responseCode = httpConnection.getResponseCode();
                // 判断是否为重定向状态码
                if (isRedirect(responseCode)) {
                    String location = httpConnection.getHeaderField("Location");
                    httpConnection.disconnect();
                    if (StrUtil.isBlank(location)) {
                        throw new IOException("OnlyOffice 下载地址重定向缺少 Location");
                    }
                    // 解析重定向目标地址，继续下一轮校验
                    currentUri = currentUri.resolve(location);
                    continue;
                }
            }

            return connection;
        }

        // 超过最大重定向次数
        throw new IOException("OnlyOffice 下载地址重定向次数超过限制");
    }

    /**
     * 解析并校验 HTTP/HTTPS URL
     *
     * @param rawUrl 原始 URL 字符串
     * @return 规范化后的 URI 对象
     * @throws URISyntaxException URL 格式不合法或协议不支持时抛出
     */
    private URI parseHttpUri(String rawUrl) throws URISyntaxException {
        if (StrUtil.isBlank(rawUrl)) {
            throw new URISyntaxException("", "OnlyOffice URL 不能为空");
        }
        URI uri = new URI(rawUrl).normalize();
        String scheme = uri.getScheme();
        // 只允许 http 和 https 协议
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new URISyntaxException(rawUrl, "OnlyOffice URL 仅支持 http/https");
        }
        if (StrUtil.isBlank(uri.getHost())) {
            throw new URISyntaxException(rawUrl, "OnlyOffice URL host 不能为空");
        }
        return uri;
    }

    /**
     * 校验回调地址与 OnlyOffice Document Server 地址是否同源
     * <p>比较 scheme、host 和 port 是否完全一致，防止 SSRF 攻击。</p>
     *
     * @param callbackUri   回调中的下载地址
     * @param onlyOfficeUri 配置的 OnlyOffice Document Server 地址
     * @throws IOException 地址不匹配时抛出
     */
    private void validateOnlyOfficeUrl(URI callbackUri, URI onlyOfficeUri) throws IOException {
        if (!normalizeScheme(callbackUri).equals(normalizeScheme(onlyOfficeUri))
                || !normalizeHost(callbackUri).equals(normalizeHost(onlyOfficeUri))
                || normalizePort(callbackUri) != normalizePort(onlyOfficeUri)) {
            throw new IOException("OnlyOffice 下载地址与配置的 Document Server 不匹配");
        }
    }

    /**
     * 规范化协议（转为小写）
     */
    private String normalizeScheme(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化主机名（转为小写）
     */
    private String normalizeHost(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化端口号（如果未指定则返回默认端口：https=443，http=80）
     */
    private int normalizePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 判断 HTTP 响应码是否为重定向
     * <p>支持 301、302、303、307、308 五种重定向状态码。</p>
     */
    private boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    /**
     * 安全地从 URL 中提取主机名（解析失败时返回空字符串，不抛异常）
     */
    private String safeHost(String rawUrl) {
        try {
            return new URI(rawUrl).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}

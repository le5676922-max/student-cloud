package im.tlgxy.cloud.module.storage.service.base;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import im.tlgxy.cloud.core.exception.system.UploadFileFailSystemException;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.constant.StorageConfigConstant;
import im.tlgxy.cloud.module.storage.model.bo.RefreshTokenCacheBO;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import im.tlgxy.cloud.module.storage.model.dto.RefreshTokenInfoDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.MicrosoftDriveParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.oauth2.service.IOAuth2Service;
import im.tlgxy.cloud.module.storage.service.StorageSourceConfigService;
import jakarta.annotation.Nullable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 微软云盘(OneDrive / SharePoint) 抽象基础服务类。
 * <p>
 * 该类封装了与 Microsoft Graph API 交互的核心逻辑，包括：
 * <ul>
 *   <li>文件的增删改查、复制、移动、重命名等基本操作</li>
 *   <li>基于 RefreshToken 的 AccessToken 自动刷新机制</li>
 *   <li>文件上传（支持大文件分片上传）和下载（支持代理/直链）</li>
 *   <li>文件夹的创建、删除等操作</li>
 * </ul>
 * 子类需要实现抽象方法以提供具体的 Graph 端点地址、认证端点地址、Client ID/Secret 等信息。
 * </p>
 *
 * @param <P> 存储源参数类型，必须继承自 {@link MicrosoftDriveParam}
 * @author zhaojun
 */
@Slf4j
public abstract class AbstractMicrosoftDriveService<P extends MicrosoftDriveParam> extends AbstractProxyTransferService<P> implements RefreshTokenService {

    /**
     * 存储源配置服务，用于读写存储源的 AccessToken、RefreshToken 等配置信息
     */
    @Resource
    private StorageSourceConfigService storageSourceConfigService;

    /**
     * 获取根文件（根目录下的文件/文件夹列表）API URI 模板
     */
    protected static final String DRIVER_ROOT_URL = "https://{graphEndPoint}/v1.0/{type}/drive/root/children?select=name,size,lastModifiedDateTime,file,@microsoft.graph.downloadUrl,@odata.nextLink,value";

    /**
     * 获取非根目录下的文件/文件夹列表 API URI 模板
     */
    protected static final String DRIVER_ITEMS_URL = "https://{graphEndPoint}/v1.0/{type}/drive/root:{path}:/children?select=name,size,lastModifiedDateTime,file,@microsoft.graph.downloadUrl,@odata.nextLink,value";

    /**
     * 获取单个文件/文件夹信息 API URI 模板
     */
    protected static final String DRIVER_ITEM_URL = "https://{graphEndPoint}/v1.0/{type}/drive/root:{path}?select=name,size,lastModifiedDateTime,file,@microsoft.graph.downloadUrl,id";

    /**
     * 操作单个文件/文件夹（删除、更新、移动等）API URI 模板
     */
    protected static final String DRIVER_ITEM_OPERATOR_URL = "https://{graphEndPoint}/v1.0/{type}/drive/root:{path}";

    /**
     * 根据 RefreshToken 获取新的 AccessToken 的 OAuth2 认证 API URI 模板
     */
    protected static final String AUTHENTICATE_URL = "https://{authenticateEndPoint}/common/oauth2/v2.0/token";

    /**
     * 创建上传会话（用于大文件分片上传）API URI 模板
     */
    protected static final String CREATE_UPLOAD_SESSION_URL = "https://{graphEndPoint}/v1.0/{type}/drive/root:{path}:/createUploadSession";

    /**
     * 复制文件 API URI 模板
     */
    private static final String DRIVER_COPY_URL = "https://{graphEndPoint}/v1.0/{type}/drive/root:{path}:/copy";

    /**
     * OneDrive / Microsoft Graph API 中标识"文件"类型的 JSON 字段名。
     * 如果返回的 JSON 中包含此字段，表示该项为文件；否则为文件夹。
     */
    private static final String ONE_DRIVE_FILE_FLAG = "file";

    /**
     * RestTemplate 实例，用于发起 HTTP 请求。
     * 使用 volatile 关键字保证多线程环境下的可见性，配合双重检查锁实现线程安全的懒加载。
     * <p>
     * 注意：默认的 RestTemplate 实现不支持 PATCH 请求，因此使用 Apache HttpClient 作为底层实现。
     * </p>
     */
    private volatile RestTemplate restTemplate;

    /**
     * 初始化存储源，在存储源加载后调用。
     * <p>
     * 初始化逻辑：
     * <ol>
     *   <li>如果 refreshTokenExpiredAt 不为空，直接使用该过期时间</li>
     *   <li>否则尝试从 AccessToken (JWT) 中解析过期时间</li>
     *   <li>如果仍然无法获取过期时间，则立即刷新 AccessToken</li>
     *   <li>最后将 Token 信息写入缓存</li>
     * </ol>
     * </p>
     */
    @Override
    public void init() {
        Integer refreshTokenExpiredAt = param.getRefreshTokenExpiredAt();
        if (refreshTokenExpiredAt == null) {
            try {
                // 尝试从 AccessToken（JWT 格式）中自动解析过期时间（exp 声明）
                JWT jwt = JWTUtil.parseToken(param.getAccessToken());
                JWTPayload payload = jwt.getPayload();
                refreshTokenExpiredAt = Convert.toInt(payload.getClaim("exp"));
                if (log.isDebugEnabled()) {
                    log.debug("初始化时尝试根据 AccessToken 自动解析到期时间: {}", refreshTokenExpiredAt);
                }
            } catch (Exception e) {
                log.warn("初始化时尝试根据 AccessToken 自动解析到期时间异常", e);
            }
        }

        if (refreshTokenExpiredAt == null) {
            // 无法获取过期时间，立即刷新 Token
            refreshAccessToken();
        } else {
            // 使用已有的 Token 信息创建缓存条目
            RefreshTokenInfoDTO tokenInfoDTO = RefreshTokenInfoDTO.success(param.getAccessToken(), param.getRefreshToken(), refreshTokenExpiredAt);
            RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.success(tokenInfoDTO));
        }
    }

    /**
     * 获取 RestTemplate 实例，使用双重检查锁定（DCL）实现线程安全的懒加载单例模式。
     * <p>
     * RestTemplate 使用 Apache HttpClient 作为底层 HTTP 客户端，支持 PATCH 等请求方法，
     * 并可根据存储源参数配置上传超时时间。
     * </p>
     *
     * @return RestTemplate 实例
     */
    public RestTemplate getRestTemplate() {
        // 双重检查锁，避免重复创建 RestTemplate 实例的同时减少锁的开销
        if (restTemplate == null) {
            synchronized (this) {
                if (restTemplate == null) {
                    restTemplate = new RestTemplate();
                    // 根据存储源参数获取上传超时时间，默认为 0（不超时）
                    int timeoutSecond = param.getProxyUploadTimeoutSecond() == null ? 0 : param.getProxyUploadTimeoutSecond();
                    RequestConfig requestConfig = RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(timeoutSecond)).build();
                    HttpClient httpClient = HttpClientBuilder.create().setDefaultRequestConfig((requestConfig)).build();
                    HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
                    restTemplate.setRequestFactory(requestFactory);
                }
            }
        }
        return restTemplate;
    }


    /**
     * 获取指定文件夹路径下的文件和文件夹列表。
     * <p>
     * 支持分页查询，如果 API 返回了 @odata.nextLink，会自动获取下一页数据，直到所有数据获取完毕。
     * 如果启用了代理下载且未配置代理域名，则自动将文件 URL 替换为代理下载地址。
     * </p>
     *
     * @param folderPath 文件夹路径（相对于存储源根目录）
     * @return 文件和文件夹列表，如果请求失败则返回空列表
     */
    @Override
    public List<FileItemResult> fileList(String folderPath) {
        // 拼接完整路径：basePath + 当前用户根目录 + 请求的文件夹路径
        String fullPath = StringUtils.concatTrimEndSlashes(param.getBasePath(), getCurrentUserBasePath(), folderPath);

        List<FileItemResult> result = new ArrayList<>();
        String nextPageLink = null;

        do {
            String requestUrl;

            // 如果有下一页链接，则优先取下一页
            // 如果没有则判断是根目录还是子目录
            if (nextPageLink != null) {
                // 对下一页链接中的特殊字符进行 URL 编码，然后解码以获取原始 URL
                nextPageLink = nextPageLink.replace("+", "%2B");
                requestUrl = URLUtil.decode(nextPageLink);
            } else if (StringUtils.SLASH.equalsIgnoreCase(fullPath) || "".equalsIgnoreCase(fullPath)) {
                // 根目录：使用根目录 API
                requestUrl = DRIVER_ROOT_URL;
            } else {
                // 子目录：使用子目录 API
                requestUrl = DRIVER_ITEMS_URL;
            }

            // 构建带有认证信息的 HTTP 请求实体
            HttpEntity<Object> entity = getAuthorizationHttpEntity();
            // 调用 Microsoft Graph API 获取文件列表
            JSONObject root = getRestTemplate().exchange(requestUrl, HttpMethod.GET, entity, JSONObject.class, getGraphEndPoint(), getType(), fullPath).getBody();
            if (root == null) {
                return Collections.emptyList();
            }

            // 解析返回的 JSON 数组，将每个 JSON 对象转换为 FileItemResult
            JSONArray fileList = root.getJSONArray("value");
            for (int i = 0; i < fileList.size(); i++) {
                JSONObject fileItem = fileList.getJSONObject(i);
                FileItemResult fileItemResult = jsonToFileItem(fileItem, folderPath);
                // 如果启用了代理下载且未配置代理域名，则将文件 URL 替换为代理下载地址
                if (param.isEnableProxyDownload() && StringUtils.isEmpty(param.getProxyDomain())) {
                    fileItemResult.setUrl(getProxyDownloadUrl(StringUtils.concat(getCurrentUserBasePath(), folderPath, fileItemResult.getName())));
                }
                result.add(fileItemResult);
            }

            // 获取下一页链接，如果存在则继续循环
            nextPageLink = root.getString("@odata.nextLink");
        } while (nextPageLink != null);

        return result;
    }

    /**
     * 获取单个文件/文件夹的信息。
     *
     * @param pathAndName 文件路径及文件名称（包含用户根目录前缀）
     * @return 文件/文件夹信息，如果不存在则返回 null
     */
    @Override
    public FileItemResult getFileItem(String pathAndName) {
        String fullPath = StringUtils.concat(getCurrentUserBasePath(), pathAndName);
        return getOriginFileItem(fullPath);
    }


    /**
     * 获取原始的 FileItem 信息，尚未按照存储源参数设置代理下载地址。
     * <p>
     * 与 {@link #getFileItem(String)} 的区别在于，此方法不会根据代理下载配置修改文件 URL，
     * 返回的是 Microsoft Graph API 的原始下载地址。
     * </p>
     *
     * @param pathAndName 文件路径及文件名称
     * @return 原始的文件信息，如果文件不存在则返回 null
     */
    public FileItemResult getOriginFileItem(String pathAndName) {
        // 调用 Microsoft Graph API 获取文件的原始 JSON 信息
        JSONObject fileItem = getFileOriginInfo(pathAndName);
        if (fileItem == null) return null;

        String folderPath = FileUtils.getParentPath(pathAndName);
        return jsonToFileItem(fileItem, folderPath);
    }

    /**
     * 调用 Microsoft Graph API 获取文件的原始 JSON 信息。
     *
     * @param pathAndName 文件路径及文件名称
     * @return 文件的原始 JSON 对象，如果不存在则返回 null
     */
    @Nullable
    private JSONObject getFileOriginInfo(String pathAndName) {
        // 拼接完整路径（包含 basePath），调用 Microsoft Graph API 获取单个文件信息
        String fullPath = StringUtils.concat(param.getBasePath(), pathAndName);
        HttpEntity<Object> entity = getAuthorizationHttpEntity();
        return getRestTemplate().exchange(DRIVER_ITEM_URL, HttpMethod.GET, entity, JSONObject.class, getGraphEndPoint(), getType(), fullPath).getBody();
    }


    /**
     * 在指定路径下创建新文件夹。
     * <p>
     * 通过 Microsoft Graph API 的 POST 请求创建文件夹，使用 "replace" 冲突策略：
     * 如果目标位置已存在同名文件夹，将被替换。
     * </p>
     *
     * @param path 文件夹路径
     * @param name 新文件夹名称
     * @return 是否创建成功
     */
    @Override
    public boolean newFolder(String path, String name) {
        path = StringUtils.trimStartSlashes(path);
        String fullPath = StringUtils.concatTrimEndSlashes(param.getBasePath(), getCurrentUserBasePath(), path);

        String requestUrl;

        // 判断是根目录还是子目录，选择对应的 API
        if (StringUtils.SLASH.equalsIgnoreCase(fullPath) || "".equalsIgnoreCase(fullPath)) {
            requestUrl = DRIVER_ROOT_URL;
        } else {
            requestUrl = DRIVER_ITEMS_URL;
        }

        // 构建请求体：包含文件夹名称、folder 标记和冲突处理策略
        HashMap<Object, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("folder", new HashMap<>());
        data.put("@microsoft.graph.conflictBehavior", "replace");

        HttpEntity<HashMap<Object, Object>> entity = getAuthorizationHttpEntity(data);
        ResponseEntity<JSONObject> responseEntity = getRestTemplate().exchange(requestUrl, HttpMethod.POST, entity, JSONObject.class, getGraphEndPoint(), getType(), fullPath);
        return responseEntity.getStatusCode().is2xxSuccessful();
    }

    /**
     * 删除指定路径下的文件夹。
     * <p>
     * 底层复用 {@link #deleteFile(String, String)} 方法，因为 Microsoft Graph API 对文件和文件夹的删除操作相同。
     * </p>
     *
     * @param path 文件夹路径
     * @param name 文件夹名称
     * @return 是否删除成功
     */
    @Override
    public boolean deleteFolder(String path, String name) {
        return deleteFile(path, name);
    }

    /**
     * 删除指定路径下的文件。
     * <p>
     * 通过 Microsoft Graph API 的 DELETE 请求删除文件或文件夹。
     * </p>
     *
     * @param path 文件路径
     * @param name 文件名称
     * @return 是否删除成功
     */
    @Override
    public boolean deleteFile(String path, String name) {
        // 拼接完整路径
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);

        HttpEntity<Object> entity = getAuthorizationHttpEntity();
        ResponseEntity<JSONObject> responseEntity = getRestTemplate().exchange(DRIVER_ITEM_OPERATOR_URL, HttpMethod.DELETE, entity, JSONObject.class, getGraphEndPoint(), getType(), fullPath);
        return responseEntity.getStatusCode().is2xxSuccessful();
    }

    /**
     * 重命名文件。
     * <p>
     * 通过 Microsoft Graph API 的 PATCH 请求更新文件的 name 属性实现重命名。
     * </p>
     *
     * @param path    文件路径
     * @param name    文件原名
     * @param newName 新文件名
     * @return 是否重命名成功
     */
    @Override
    public boolean renameFile(String path, String name, String newName) {
        // 拼接完整路径
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);

        // 构建请求体，只包含要修改的 name 字段
        JSONObject jsonObject = new JSONObject().fluentPut("name", newName);

        HttpEntity<Object> entity = getAuthorizationHttpEntity(jsonObject);
        ResponseEntity<JSONObject> responseEntity = getRestTemplate().exchange(DRIVER_ITEM_OPERATOR_URL, HttpMethod.PATCH, entity, JSONObject.class, getGraphEndPoint(), getType(), fullPath);
        return responseEntity.getStatusCode().is2xxSuccessful();
    }

    /**
     * 重命名文件夹。
     * <p>
     * 底层复用 {@link #renameFile(String, String, String)} 方法，因为 Microsoft Graph API 对文件和文件夹的重命名操作相同。
     * </p>
     *
     * @param path    文件夹路径
     * @param name    文件夹原名
     * @param newName 新文件夹名称
     * @return 是否重命名成功
     */
    @Override
    public boolean renameFolder(String path, String name, String newName) {
        return renameFile(path, name, newName);
    }

    /**
     * 获取文件上传地址。
     * <p>
     * 如果启用了代理上传，则返回代理上传地址；否则通过 Microsoft Graph API 创建上传会话获取 OneDrive 直传地址。
     * </p>
     *
     * @param path 文件路径
     * @param name 文件名称
     * @param size 文件大小（字节）
     * @return 文件上传地址
     */
    @Override
    public String getUploadUrl(String path, String name, Long size) {
        if (param.isEnableProxyUpload()) {
            return super.getProxyUploadUrl(path, name);
        }
        return getOneDriveUploadUrl(StringUtils.concat(getCurrentUserBasePath(), path), name);
    }

    /**
     * 通过 Microsoft Graph API 创建上传会话，获取 OneDrive 直传地址。
     * <p>
     * 此方法调用 Microsoft Graph API 的 createUploadSession 端点，返回可用于直接上传文件的 URL。
     * </p>
     *
     * @param path 文件路径
     * @param name 文件名称
     * @return OneDrive 上传地址
     * @throws SystemException 如果获取上传地址失败（返回值为空）
     */
    private String getOneDriveUploadUrl(String path, String name) {
        // 拼接完整路径
        String fullPath = StringUtils.concat(param.getBasePath(), path, name);

        HttpEntity<Object> entity = getAuthorizationHttpEntity();
        ResponseEntity<JSONObject> responseEntity = getRestTemplate().exchange(CREATE_UPLOAD_SESSION_URL,
                HttpMethod.POST, entity, JSONObject.class,
                getGraphEndPoint(), getType(), fullPath);

        JSONObject responseEntityBody = responseEntity.getBody();
        if (responseEntityBody == null) {
            throw new SystemException("获取上传地址失败, 返回值为空.");
        }
        return responseEntityBody.getString("uploadUrl");
    }

    /**
     * 上传文件到 OneDrive。
     * <p>
     * 先通过 Microsoft Graph API 创建上传会话获取上传地址，然后使用 HTTP PUT 请求上传文件内容。
     * 上传时设置 Content-Range 头以支持一次性完整上传整个文件。
     * </p>
     *
     * @param pathAndName 文件路径及文件名称
     * @param inputStream 文件输入流
     * @param size        文件大小（字节）
     * @throws IOException 如果上传过程中发生 I/O 异常或超时
     */
    @Override
    public void uploadFile(String pathAndName, InputStream inputStream, Long size) throws IOException {
        // 获取文件夹路径和文件名
        String fullPath = StringUtils.concat(getCurrentUserBasePath(), pathAndName);
        String folderPath = FileUtils.getParentPath(fullPath);
        String fileName = FileUtils.getName(fullPath);
        // 获取 OneDrive 上传地址
        String uploadUrl = getOneDriveUploadUrl(folderPath, fileName);

        try {
            // 使用 HTTP PUT 请求上传文件，设置 Content-Range 头标识完整文件
            getRestTemplate().execute(uploadUrl, HttpMethod.PUT, request -> {
                HttpHeaders headers = request.getHeaders();
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                headers.setContentLength(size);
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes 0-" + (size - 1) + StringUtils.SLASH + size);
                StreamUtils.copy(inputStream, request.getBody());
            }, clientHttpResponse -> {
                // 检查上传响应状态码
                if (!clientHttpResponse.getStatusCode().is2xxSuccessful()) {
                    throw new UploadFileFailSystemException(this.getStorageTypeEnum(), pathAndName, size,
                            clientHttpResponse.getStatusCode().value(), clientHttpResponse.getStatusText());
                }
                return null;
            });
        } catch (Exception e) {
            // 特殊处理超时异常
            if (e instanceof ResourceAccessException && e.getMessage() != null && e.getMessage().contains("Timeout on")) {
                throw new BizException(ErrorCode.BIZ_UPLOAD_FILE_TIMEOUT_ERROR);
            }
            throw new UploadFileFailSystemException(this.getStorageTypeEnum(), pathAndName, size, 500, e.getMessage(), e);
        }
    }

    /**
     * 获取文件下载地址。
     * <p>
     * 如果启用了代理下载且未配置代理域名，则返回代理下载地址；
     * 否则调用 Microsoft Graph API 获取文件的原始下载直链。
     * </p>
     * <p>
     * 路径契约：pathAndName 必须是相对存储源 basePath 的绝对路径（调用方已拼好用户根目录/分享 sharePath）。
     * 本方法内部使用 getOriginFileItem（仅拼接 param.basePath），不会二次拼接 getCurrentUserBasePath，
     * 避免登录用户访问分享/直链下载时路径重复拼接。
     * </p>
     *
     * @param pathAndName 相对 basePath 的绝对路径（已含用户根目录/sharePath）
     * @return 文件下载地址
     * @throws NotFoundAccessException 如果文件不存在
     */
    @Override
    public String getDownloadUrl(String pathAndName) {
        if (param.isEnableProxyDownload() && StringUtils.isEmpty(param.getProxyDomain())) {
            return getProxyDownloadUrl(pathAndName);
        }
        // 此处 pathAndName 已是包含用户根目录/分享路径的完整路径(相对 param.basePath),
        // 必须用 getOriginFileItem(仅拼 param.basePath); 不能用 getFileItem(会再拼一次
        // getCurrentUserBasePath 导致重复, 登录用户访问分享/直链下载时路径错误).
        FileItemResult fileItem = getOriginFileItem(pathAndName);
        if (fileItem == null) {
            throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }
        return fileItem.getUrl();
    }

    /**
     * 以流的形式代理下载文件（将文件内容直接写入 HTTP 响应输出流）。
     * <p>
     * 先从 Microsoft Graph API 获取文件原始下载地址，然后通过 HTTP GET 请求获取文件流，
     * 最终将文件内容写入当前请求的 HTTP 响应中。
     * </p>
     * <p>
     * 路径契约：pathAndName 是相对 basePath 的绝对路径（调用方已拼好用户根目录/sharePath），
     * 实现内部不得再调用 getCurrentUserBasePath() 二次拼接。
     * </p>
     *
     * @param pathAndName 相对 basePath 的绝对路径
     * @return 始终返回 null（文件内容已直接写入响应流）
     * @throws IOException               如果下载过程中发生 I/O 异常
     * @throws NotFoundAccessException   如果文件不存在
     */
    @Override
    public ResponseEntity<org.springframework.core.io.Resource> downloadToStream(String pathAndName) throws IOException {
        // 获取文件的原始信息（包含直链下载地址）
        FileItemResult fileItem = getOriginFileItem(pathAndName);
        if (fileItem == null) {
            throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }
        Long fileSize = fileItem.getSize();
        String fileName = fileItem.getName();
        String url = fileItem.getUrl();

        // 通过 HTTP GET 请求获取文件流，并直接写入当前请求的 HTTP 响应中
        getRestTemplate().execute(url, HttpMethod.GET, null, clientHttpResponse -> {
            InputStream inputStream = clientHttpResponse.getBody();
            RequestHolder.writeFile(inputStream, fileName, fileSize, false, param.isProxyLinkForceDownload());
            return null;
        });
        return null;
    }

    /**
     * 复制文件到目标路径。
     * <p>
     * 通过 Microsoft Graph API 的 copy 端点实现文件复制。
     * 需要先获取目标文件夹的 ID，然后在请求体中指定目标父级引用。
     * </p>
     *
     * @param path       源文件路径
     * @param name       源文件名称
     * @param targetPath 目标文件路径
     * @param targetName 目标文件名称
     * @return 是否复制成功
     * @throws BizException 如果目标文件夹不存在
     */
    @Override
    public boolean copyFile(String path, String name, String targetPath, String targetName) {
        // 拼接源文件完整路径
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
        // 拼接目标文件夹路径
        String targetFullPath = StringUtils.concat(getCurrentUserBasePath(), targetPath);

        // 获取目标文件夹的原始信息（需要其 ID）
        JSONObject fileOriginInfo = getFileOriginInfo(targetFullPath);
        if (fileOriginInfo == null) {
            throw new BizException(ErrorCode.BIZ_FOLDER_NOT_EXIST);
        }

        // 获取目标文件夹的 ID，作为复制的父级引用
        String targetPathId = fileOriginInfo.getString("id");
        JSONObject jsonObject = new JSONObject()
                .fluentPut("name", targetName)
                .fluentPut("parentReference", new JSONObject().fluentPut("id", targetPathId))
                .fluentPut("@microsoft.graph.conflictBehavior", "replace");

        HttpEntity<Object> entity = getAuthorizationHttpEntity(jsonObject);
        ResponseEntity<JSONObject> responseEntity = getRestTemplate().exchange(DRIVER_COPY_URL,
                                                                                    HttpMethod.POST,
                                                                                    entity,
                                                                                    JSONObject.class,
                                                                                    getGraphEndPoint(),
                                                                                    getType(),
                                                                                    fullPath);
        return responseEntity.getStatusCode().is2xxSuccessful();
    }

    /**
     * 复制文件夹到目标路径。
     * <p>
     * 底层复用 {@link #copyFile(String, String, String, String)} 方法，因为 Microsoft Graph API 对文件和文件夹的复制操作相同。
     * </p>
     *
     * @param path       源文件夹路径
     * @param name       源文件夹名称
     * @param targetPath 目标文件夹路径
     * @param targetName 目标文件夹名称
     * @return 是否复制成功
     */
    @Override
    public boolean copyFolder(String path, String name, String targetPath, String targetName) {
        return copyFile(path, name, targetPath, targetName);
    }

    /**
     * 移动文件到目标路径。
     * <p>
     * 通过 Microsoft Graph API 的 PATCH 请求更新文件的 parentReference（父级引用）实现文件移动。
     * 需要先获取目标文件夹的 ID。
     * </p>
     *
     * @param path       源文件路径
     * @param name       源文件名称
     * @param targetPath 目标文件路径
     * @param targetName 目标文件名称
     * @return 是否移动成功
     * @throws BizException 如果目标文件夹不存在
     */
    @Override
    public boolean moveFile(String path, String name, String targetPath, String targetName) {
        // 拼接源文件完整路径
        String fullPath = StringUtils.concat(param.getBasePath(), getCurrentUserBasePath(), path, name);
        // 拼接目标文件夹路径
        String targetFullPath = StringUtils.concat(getCurrentUserBasePath(), targetPath);

        // 获取目标文件夹的原始信息（需要其 ID）
        JSONObject fileOriginInfo = getFileOriginInfo(targetFullPath);
        if (fileOriginInfo == null) {
            throw new BizException(ErrorCode.BIZ_FOLDER_NOT_EXIST);
        }

        // 获取目标文件夹的 ID，作为移动的父级引用
        String targetPathId = fileOriginInfo.getString("id");

        // 构建请求体：指定新名称、新父级引用和冲突处理策略
        JSONObject jsonObject = new JSONObject()
                .fluentPut("name", targetName)
                .fluentPut("parentReference", new JSONObject().fluentPut("id", targetPathId))
                .fluentPut("@microsoft.graph.conflictBehavior", "replace");

        HttpEntity<Object> entity = getAuthorizationHttpEntity(jsonObject);
        // 使用 PATCH 请求更新文件的父级引用，实现移动
        ResponseEntity<JSONObject> responseEntity = getRestTemplate().exchange(DRIVER_ITEM_OPERATOR_URL,
                                                                                    HttpMethod.PATCH,
                                                                                    entity,
                                                                                    JSONObject.class,
                                                                                    getGraphEndPoint(),
                                                                                    getType(),
                                                                                    fullPath);
        return responseEntity.getStatusCode().is2xxSuccessful();
    }

    /**
     * 移动文件夹到目标路径。
     * <p>
     * 底层复用 {@link #moveFile(String, String, String, String)} 方法，因为 Microsoft Graph API 对文件和文件夹的移动操作相同。
     * </p>
     *
     * @param path       源文件夹路径
     * @param name       源文件夹名称
     * @param targetPath 目标文件夹路径
     * @param targetName 目标文件夹名称
     * @return 是否移动成功
     */
    @Override
    public boolean moveFolder(String path, String name, String targetPath, String targetName) {
        return moveFile(path, name, targetPath, targetName);
    }

    /**
     * 获取存储类型（OneDrive 或 SharePoint），用于构建 Graph API URL 中的 {type} 占位符。
     * <p>
     * 对于 OneDrive，返回 "me"；对于 SharePoint，返回 "sites/{siteId}"。
     * </p>
     *
     * @return Graph API 的存储类型标识
     */
    public abstract String getType();

    /**
     * 获取 Microsoft Graph API 的终结点地址。
     * <p>
     * 对于不同版本的 OneDrive（国际版 / 世纪互联版等），此地址会不同。
     * </p>
     *
     * @return Graph 终结点地址
     */
    public abstract String getGraphEndPoint();


    /**
     * 获取 OAuth2 认证终结点地址。
     * <p>
     * 对于不同版本的 OneDrive（国际版 / 世纪互联版等），此地址会不同。
     * </p>
     *
     * @return 认证终结点地址
     */
    public abstract String getAuthenticateEndPoint();

    /**
     * 获取应用程序（Client）ID，用于 OAuth2 认证。
     *
     * @return Client ID
     */
    public abstract String getClientId();

    /**
     * 获取 OAuth2 认证的重定向地址（Redirect URI）。
     *
     * @return 重定向地址
     */
    public abstract String getRedirectUri();

    /**
     * 获取应用程序密钥（Client Secret），用于 OAuth2 认证。
     *
     * @return Client Secret 密钥
     */
    public abstract String getClientSecret();

    /**
     * 获取 OAuth2 认证的 API 权限范围（Scope）。
     *
     * @return API 权限范围
     */
    public abstract String getScope();


    /**
     * 刷新当前存储源的 AccessToken。
     * <p>
     * 使用 RefreshToken 通过 OAuth2 认证端点获取新的 AccessToken 和 RefreshToken，
     * 并将新的 Token 信息更新到数据库和缓存中。
     * </p>
     * <p>
     * 刷新流程：
     * <ol>
     *   <li>调用 getAndRefreshToken() 获取新的 Token</li>
     *   <li>验证获取的 Token 不为空</li>
     *   <li>更新数据库中的 AccessToken、RefreshToken 和过期时间</li>
     *   <li>更新缓存中的 Token 信息</li>
     * </ol>
     * </p>
     *
     * @throws SystemException 如果刷新过程中发生异常或获取的 Token 为空
     */
    @Override
    public void refreshAccessToken() {
        try {
            // 调用 OAuth2 认证端点刷新 Token
            RefreshTokenInfoDTO tokenInfoDTO = getAndRefreshToken();

            if (tokenInfoDTO.getAccessToken() == null || tokenInfoDTO.getRefreshToken() == null) {
                throw new SystemException("存储源 " + storageId + " 刷新令牌失败, 获取到令牌为空.");
            }

            // 从数据库中查找对应的配置记录
            StorageSourceConfig accessTokenConfig = storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.ACCESS_TOKEN_KEY);
            StorageSourceConfig refreshTokenConfig = storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.REFRESH_TOKEN_KEY);
            StorageSourceConfig refreshTokenExpiredAtConfig = storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.REFRESH_TOKEN_EXPIRED_AT_KEY);
            // 更新配置值
            accessTokenConfig.setValue(tokenInfoDTO.getAccessToken());
            refreshTokenConfig.setValue(tokenInfoDTO.getRefreshToken());
            refreshTokenExpiredAtConfig.setValue(String.valueOf(tokenInfoDTO.getExpiredAt()));

            // 批量更新数据库中的配置
            storageSourceConfigService.updateBatch(storageId, Arrays.asList(accessTokenConfig, refreshTokenConfig, refreshTokenExpiredAtConfig));
            // 更新缓存
            RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.success(tokenInfoDTO));
        } catch (Exception e) {
            // 刷新失败时记录失败状态到缓存
            RefreshTokenCacheBO.putRefreshTokenInfo(storageId, RefreshTokenCacheBO.RefreshTokenInfo.fail("AccessToken 刷新失败: " + e.getMessage()));
            throw new SystemException("存储源 " + storageId + " 刷新令牌失败, 获取时发生异常.", e);

        }
    }


    /**
     * 将 Microsoft Graph API 返回的单个文件/文件夹 JSON 对象转换为 {@link FileItemResult} 对象。
     * <p>
     * 转换逻辑：
     * <ul>
     *   <li>提取 name、size、lastModifiedDateTime 等基本属性</li>
     *   <li>根据 JSON 中是否包含 "file" 字段判断是文件还是文件夹</li>
     *   <li>如果是文件，提取 @microsoft.graph.downloadUrl 作为下载地址</li>
     *   <li>如果配置了代理域名，则将下载地址的主机名替换为代理域名</li>
     * </ul>
     * </p>
     *
     * @param jsonObject Microsoft Graph API 返回的 JSON 对象
     * @param folderPath 文件夹路径
     * @return 转换后的 FileItemResult 对象
     */
    private FileItemResult jsonToFileItem(JSONObject jsonObject, String folderPath) {
        FileItemResult fileItemResult = new FileItemResult();
        fileItemResult.setName(jsonObject.getString("name"));
        fileItemResult.setSize(jsonObject.getLong("size"));
        fileItemResult.setTime(jsonObject.getDate("lastModifiedDateTime"));

        // 如果 JSON 中包含 "file" 字段，表示是文件类型
        if (jsonObject.containsKey(ONE_DRIVE_FILE_FLAG)) {
            String originUrl = jsonObject.getString("@microsoft.graph.downloadUrl");
            // 如果配置了代理域名，替换原始下载地址的主机名为代理域名
            if (StringUtils.isNotEmpty(param.getProxyDomain())) {
                originUrl = StringUtils.replaceHost(originUrl, param.getProxyDomain());
            }

            fileItemResult.setUrl(originUrl);
            fileItemResult.setType(FileTypeEnum.FILE);
        } else {
            // 不包含 "file" 字段，表示是文件夹类型
            fileItemResult.setType(FileTypeEnum.FOLDER);
        }
        fileItemResult.setPath(folderPath);
        return fileItemResult;
    }


    /**
     * 获取存储源默认的 HttpEntity 对象（无请求体）。
     * <p>
     * 该对象默认包含了当前存储源的 AccessToken（通过 Bearer 认证头），
     * 可用于需要认证的 Microsoft Graph API 请求。
     * </p>
     *
     * @return 包含认证信息的 HttpEntity 对象
     */
    private HttpEntity<Object> getAuthorizationHttpEntity() {
       return getAuthorizationHttpEntity(null);
    }


    /**
     * 获取存储源默认的 HttpEntity 对象（带请求体）。
     * <p>
     * 该对象默认包含了当前存储源的 AccessToken（通过 Bearer 认证头），
     * 可用于需要认证的 Microsoft Graph API 请求。
     * </p>
     *
     * @param   body 请求体
     * @param   <T>  请求体类型
     * @return  包含认证信息和请求体的 HttpEntity 对象
     */
    private <T> HttpEntity<T> getAuthorizationHttpEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        // 检查 AccessToken 是否过期，如果过期则自动刷新，然后获取有效的 AccessToken
        String accessToken = checkExpiredAndGetAccessToken();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(body, headers);
    }

    /**
     * 获取存储源元数据信息。
     * <p>
     * 根据配置返回存储源的上传类型（代理上传或微软直传），以及其他元数据属性。
     * </p>
     *
     * @return 存储源元数据对象
     */
    @Override
    public StorageSourceMetadata getStorageSourceMetadata() {
        StorageSourceMetadata storageSourceMetadata = new StorageSourceMetadata();
        // 根据是否启用代理上传，设置上传类型
        if (param.isEnableProxyUpload()) {
            storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.PROXY);
        } else {
            storageSourceMetadata.setUploadType(StorageSourceMetadata.UploadType.MICROSOFT);
        }
        // OneDrive 上传前不需要预先创建文件夹
        storageSourceMetadata.setNeedCreateFolderBeforeUpload(false);
        return storageSourceMetadata;
    }

    /**
     * 使用 RefreshToken 通过 OAuth2 认证端点刷新 AccessToken。
     * <p>
     * 向 Microsoft OAuth2 认证端点发送 POST 请求，携带 client_id、redirect_uri、
     * client_secret、refresh_token 和 grant_type=refresh_token 参数，
     * 换取新的 AccessToken 和 RefreshToken。
     * </p>
     *
     * @return 包含新 AccessToken、RefreshToken 和过期时间的 DTO
     * @throws SystemException 如果认证请求失败（非 200 响应）
     */
    private RefreshTokenInfoDTO getAndRefreshToken() {
        // 从数据库中获取当前存储的 RefreshToken
        StorageSourceConfig refreshStorageSourceConfig =
                storageSourceConfigService.findByStorageIdAndName(storageId, StorageConfigConstant.REFRESH_TOKEN_KEY);

        // 构建 OAuth2 刷新令牌请求参数
        String param = "client_id=" + getClientId() +
                "&redirect_uri=" + getRedirectUri() +
                "&client_secret=" + getClientSecret() +
                "&refresh_token=" + refreshStorageSourceConfig.getValue() +
                "&grant_type=refresh_token";

        if (log.isDebugEnabled()) {
            log.debug("{} 尝试刷新令牌, 请求参数: {}", getStorageSimpleInfo(), param);
        }

        // 向 OAuth2 认证端点发送 POST 请求
        String authenticateUrl = AUTHENTICATE_URL.replace("{authenticateEndPoint}", getAuthenticateEndPoint());
        HttpResponse response = HttpUtil.createPost(authenticateUrl)
                .body(param, ContentType.FORM_URLENCODED.getValue())
                .execute();

        String responseBody = response.body();
        int responseStatus = response.getStatus();

        if (log.isDebugEnabled()) {
            log.debug("{} 刷新令牌完成. 响应状态码: {}, 响应体: {}", getStorageSimpleInfo(), responseStatus, responseBody);
        }

        // 检查响应状态码，只有 200 才表示成功
        if (responseStatus != HttpStatus.OK.value()) {
            throw new SystemException(responseBody);
        }

        // 解析响应 JSON，提取 AccessToken、RefreshToken 和过期时间
        JSONObject jsonBody = JSONObject.parseObject(responseBody);
        String accessToken = jsonBody.getString(IOAuth2Service.ACCESS_TOKEN_FIELD_NAME);
        String refreshToken = jsonBody.getString(IOAuth2Service.REFRESH_TOKEN_FIELD_NAME);
        Integer expiresIn = jsonBody.getInteger(IOAuth2Service.EXPIRES_IN_FIELD_NAME);
        return RefreshTokenInfoDTO.success(accessToken, refreshToken, expiresIn);
    }

    /**
     * 销毁资源，在存储源关闭/清理时调用。
     * <p>
     * 关闭 RestTemplate 的底层 HTTP 客户端连接，释放网络资源。
     * </p>
     */
    @Override
    public void destroy() {
        // 如果 RestTemplate 底层的 HttpClient 是可关闭的，则关闭它
        if (restTemplate != null && restTemplate.getRequestFactory() instanceof CloseableHttpClient) {
            try {
                ((CloseableHttpClient) restTemplate.getRequestFactory()).close();
            } catch (IOException e) {
                log.error("关闭 {} 的 HTTP 客户端失败.", getStorageSimpleInfo(), e);
            }
        }
    }

    /**
     * 检查 AccessToken 是否过期，如果过期则自动刷新并返回新的 AccessToken。
     * <p>
     * 使用双重检查锁定（DCL）机制确保同一存储源只有一个线程执行刷新操作，
     * 避免并发环境下的重复刷新。
     * </p>
     * <p>
     * 刷新流程：
     * <ol>
     *   <li>从缓存中获取 RefreshTokenInfo</li>
     *   <li>如果缓存为空或已过期，使用 synchronized 锁住 storageId 相关的字符串</li>
     *   <li>再次检查缓存（第二次检查），确认是否已被其他线程刷新</li>
     *   <li>如果仍然过期或为空，执行刷新操作</li>
     *   <li>返回有效的 AccessToken</li>
     * </ol>
     * </p>
     *
     * @return 有效的 AccessToken
     * @throws SystemException 如果刷新失败，缓存中无有效的 Token 信息
     */
    private String checkExpiredAndGetAccessToken() {
        // 第一次检查：从缓存中获取 Token 信息
        RefreshTokenCacheBO.RefreshTokenInfo refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);

        if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
            // 使用双重检查锁定机制，确保同一个 storageId 只会有一个线程在刷新 AccessToken
            // intern() 确保 JVM 字符串池中的同一个对象，实现精确的锁粒度
            synchronized (("storage-refresh-" + storageId).intern()) {
                // 双重检查，再次从缓存中获取，确认是否其他线程已经刷新过
                refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);
                if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
                    if (refreshTokenInfo == null || refreshTokenInfo.isExpired()) {
                        log.info("{} AccessToken 未获取或已过期, 尝试刷新: {}", getStorageSimpleInfo(), refreshTokenInfo);
                        refreshAccessToken();
                        refreshTokenInfo = RefreshTokenCacheBO.getRefreshTokenInfo(storageId);
                    }
                }
            }
        }

        // 最终检查：确保 Token 不为空
        if (refreshTokenInfo == null) {
            throw new SystemException("存储源 " + storageId + " AccessToken 刷新失败: 未找到刷新令牌信息.");
        }

        return refreshTokenInfo.getData().getAccessToken();
    }

}
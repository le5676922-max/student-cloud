package im.tlgxy.cloud.module.link.service;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.exception.core.ErrorPageBizException;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.HttpUtil;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.link.model.entity.ShortLink;
import im.tlgxy.cloud.module.log.model.entity.DownloadLog;
import im.tlgxy.cloud.module.log.service.DownloadLogService;
import im.tlgxy.cloud.module.storage.annotation.LinkRateLimiter;
import im.tlgxy.cloud.module.storage.annotation.RefererCheck;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 链接下载服务
 * <p>
 * 处理直链和短链的下载请求，是整个链接下载流程的核心服务类。
 * 主要功能包括：
 * <ul>
 *   <li>处理直链下载（handlerDirectLink）：根据存储源 key 和文件路径直接下载文件</li>
 *   <li>处理短链下载（handlerShortLink）：根据短链 key 查找原始文件并触发下载</li>
 *   <li>统一的下载处理逻辑（handlerDownloadGetUrl）：包括权限校验、文件获取、日志记录和响应构建</li>
 * </ul>
 * <p>
 * 安全控制：
 * <ul>
 *   <li>通过 {@link RefererCheck} 注解进行 Referer 防盗链校验</li>
 *   <li>通过 {@link LinkRateLimiter} 注解进行访问频率限制</li>
 *   <li>检查系统配置中是否允许直链/短链功能</li>
 *   <li>检查存储源是否启用</li>
 *   <li>检查文件是否在禁止下载的目录中</li>
 * </ul>
 *
 * @author zhaojun
 */
@Slf4j
@Service
public class LinkDownloadService {

    /**
     * 存储源服务，用于查询存储源信息
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 下载日志服务，用于记录下载行为
     */
    @Resource
    private DownloadLogService downloadLogService;

    /**
     * 系统配置服务，用于获取系统全局配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 短链服务，用于查询短链信息
     */
    @Resource
    private ShortLinkService shortLinkService;

    /**
     * 过滤配置服务，用于检查文件是否在禁止下载目录中
     */
    @Resource
    private FilterConfigService filterConfigService;

    /**
     * 已过期的短链 key 集合，用于缓存已被判定为过期的短链，
     * 避免重复查询数据库。注意：该集合在应用重启后会清空。
     */
    private final Set<String> expireKeySet = new HashSet<>();

    /**
     * 处理直链下载请求
     * <p>
     * 根据存储源 key 和文件路径直接处理文件下载。
     * 该方法被 {@link RefererCheck} 和 {@link LinkRateLimiter} 注解标记，
     * 会自动进行 Referer 防盗链校验和访问频率限制。
     *
     * @param   storageKey
     *          存储源 key，用于定位具体的存储源
     *
     * @param   filePath
     *          文件在存储源中的路径
     *
     * @return  ResponseEntity 包含下载响应（302 重定向或文件内容）
     */
    @RefererCheck
    @LinkRateLimiter
    public ResponseEntity<?> handlerDirectLink(String storageKey, String filePath) {
        // 检查系统是否允许直链
        SystemConfigDTO systemConfigDTO = systemConfigService.getSystemConfig();
        if (BooleanUtils.isNotTrue(systemConfigDTO.getShowPathLink())) {
            throw new ForbiddenAccessException(ErrorCode.BIZ_DIRECT_LINK_NOT_ALLOWED);
        }
        return handlerDownloadGetUrl(storageKey, filePath, null, DownloadLog.DOWNLOAD_TYPE_DIRECT_LINK);
    }

    /**
     * 处理短链下载请求
     * <p>
     * 根据短链 key 查找原始文件路径，然后触发文件下载。
     * 该方法被 {@link RefererCheck} 和 {@link LinkRateLimiter} 注解标记，
     * 会自动进行 Referer 防盗链校验和访问频率限制。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>检查短链是否在过期缓存中</li>
     *   <li>检查系统是否允许短链功能</li>
     *   <li>根据短链 key 查询短链记录</li>
     *   <li>检查短链是否过期</li>
     *   <li>获取存储源 key 和文件路径，调用统一下载方法</li>
     * </ol>
     *
     * @param   shortKey
     *          短链 key，用于查找对应的短链记录
     *
     * @return  ResponseEntity 包含下载响应（302 重定向或文件内容）
     *
     * @throws IOException 处理下载过程中可能出现的 IO 异常
     */
    @RefererCheck
    @LinkRateLimiter
    public ResponseEntity<?> handlerShortLink(String shortKey) throws IOException {
        // 从缓存中判断是否短链是否过期
        if (expireKeySet.contains(shortKey)) {
            throw new ForbiddenAccessException(ErrorCode.BIZ_SHORT_LINK_EXPIRED);
        }

        // 判断是否允许生成短链.
        SystemConfigDTO systemConfigDTO = systemConfigService.getSystemConfig();
        if (BooleanUtils.isNotTrue(systemConfigDTO.getShowShortLink())) {
            throw new ForbiddenAccessException(ErrorCode.BIZ_SHORT_LINK_NOT_ALLOWED);
        }

        // 判断短链是否存在
        ShortLink shortLink = shortLinkService.findByKey(shortKey);
        if (shortLink == null) {
            throw new NotFoundAccessException(ErrorCode.BIZ_SHORT_LINK_NOT_FOUNT);
        }

        // 判断短链是否过期
        if (shortLink.getExpireDate() != null) {
            DateTime now = DateUtil.date();
            boolean isExpire = now.isAfter(shortLink.getExpireDate());
            if (isExpire) {
                // 将过期 key 加入缓存，避免重复查询
                expireKeySet.add(shortKey);
                throw new ForbiddenAccessException(ErrorCode.BIZ_SHORT_LINK_EXPIRED);
            }
        }

        // 获取实际文件路径，下载并记录日志
        Integer storageId = shortLink.getStorageId();
        String storageKey = storageSourceService.findStorageKeyById(storageId);
        String filePath = shortLink.getUrl();
        return handlerDownloadGetUrl(storageKey, filePath, shortKey, DownloadLog.DOWNLOAD_TYPE_SHORT_LINK);
    }

    /**
     * 处理指定存储源的下载请求（内部统一处理方法）
     * <p>
     * 该方法封装了直链和短链下载的共同逻辑，包括：
     * <ol>
     *   <li>获取并校验存储源服务实例</li>
     *   <li>检查存储源是否启用</li>
     *   <li>检查文件是否在禁止下载目录中（被禁止则重定向到 Forbidden 页面）</li>
     *   <li>获取文件的下载链接</li>
     *   <li>记录下载日志（根据系统配置决定是否记录）</li>
     *   <li>处理特殊文件格式（如 m3u8 直接返回内容而非重定向）</li>
     *   <li>构建 302 重定向响应（普通文件）</li>
     * </ol>
     *
     * @param   storageKey
     *          存储源 key
     *
     * @param   filePath
     *          文件在存储源中的路径
     *
     * @param   shortKey
     *          短链接 key（直链下载时为 null）
     *
     * @param   downloadType
     *          下载类型：直链下载({@link DownloadLog#DOWNLOAD_TYPE_DIRECT_LINK})或短链下载({@link DownloadLog#DOWNLOAD_TYPE_SHORT_LINK})
     *
     * @return  ResponseEntity 包含下载响应：
     *          <ul>
     *            <li>普通文件：302 重定向到实际下载地址</li>
     *            <li>m3u8 文件：直接返回文件内容</li>
     *            <li>禁止下载的文件：302 重定向到 Forbidden 页面</li>
     *          </ul>
     */
    private ResponseEntity<?> handlerDownloadGetUrl(String storageKey, String filePath, String shortKey, String downloadType) {
        // 文件别名用于日志和错误提示
        String fileAlias = StringUtils.equals(downloadType, DownloadLog.DOWNLOAD_TYPE_DIRECT_LINK) ? filePath : shortKey;

        // 获取存储源 Service
        AbstractBaseFileService<?> fileService;
        try {
            fileService = StorageSourceContext.getByStorageKey(storageKey);
        } catch (InvalidStorageSourceBizException e) {
            throw new ErrorPageBizException("无效的或初始化失败的存储源 [" + storageKey + "] 文件 [" + fileAlias + "] 下载链接异常, 无法下载.", e);
        }

        if (fileService == null) {
            throw new ErrorPageBizException("未找到存储源 [" + storageKey + "] 文件 [" + fileAlias + "] 下载链接异常, 无法下载.");
        }

        // 检查存储源是否已启用
        StorageSource storageSource = storageSourceService.findByStorageKey(storageKey);
        Boolean enable = storageSource.getEnable();
        if (!enable) {
            throw new ErrorPageBizException("未启用的存储源 [" + storageKey + "] 文件 [" + fileAlias + "] 下载链接异常, 无法下载.");
        }

        // 检查是否访问了禁止下载的目录
        if (filterConfigService.checkFileIsDisableDownload(storageSource.getId(), filePath)) {
            // 获取 Forbidden 页面地址，重定向到禁止访问页面
            return ResponseEntity.status(302)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, private")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header(HttpHeaders.LOCATION, systemConfigService.getForbiddenUrl())
                    .build();
        }

        // 获取文件下载链接
        String downloadUrl;
        try {
            downloadUrl = fileService.getDownloadUrl(filePath);
        } catch (NotFoundAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorPageBizException("获取存储源 [" + storageKey + "] 文件 [" + fileAlias + "] 下载链接异常, 无法下载.", e);
        }

        // 判断下载链接是否为空
        if (StringUtils.isEmpty(downloadUrl)) {
            throw new ErrorPageBizException("获取存储源 [" + storageKey + "] 文件 [" + fileAlias + "] 下载链接为空, 无法下载.");
        }

        // 记录下载日志（根据系统配置决定）
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        Boolean recordDownloadLog = systemConfig.getRecordDownloadLog();
        if (BooleanUtils.isTrue(recordDownloadLog)) {
            DownloadLog downloadLog = new DownloadLog(downloadType, filePath, storageKey, shortKey);
            downloadLogService.save(downloadLog);
        }

        // 判断下载链接是否为 m3u8 格式, 如果是则返回 m3u8 内容（而非重定向）
        if (StringUtils.equalsIgnoreCase(FileUtil.extName(filePath), "m3u8")) {
            String textContent = HttpUtil.getTextContent(downloadUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl;charset=utf-8"));
            ContentDisposition contentDisposition = ContentDisposition
                    .builder("attachment")
                    .filename(FileUtils.getName(filePath), StandardCharsets.UTF_8)
                    .build();
            headers.setContentDisposition(contentDisposition);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(textContent);
        }

        // 普通文件：返回 302 重定向到实际下载地址
        return ResponseEntity.status(302)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .header(HttpHeaders.LOCATION, downloadUrl)
                .build();
    }

}
package im.tlgxy.cloud.module.share.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.entity.ShareLink;
import im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum;
import im.tlgxy.cloud.module.share.model.result.ShareFileInfoResult;
import im.tlgxy.cloud.module.share.model.result.ShareLinkResult;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分享文件操作服务
 * <p>
 * 专门处理分享文件的访问逻辑，包括获取分享文件列表、获取下载地址和获取单个文件信息。
 * 与普通文件访问的区别在于：分享文件访问需要绕过用户基础路径限制，
 * 通过 {@link ShareAccessContext} 设置分享访问上下文，使文件服务能够以分享者的视角访问文件。
 * </p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>验证分享链接的有效性（存在且未过期）</li>
 *   <li>验证分享密码（如果设置了密码）</li>
 *   <li>设置分享访问上下文（ShareAccessContext），让文件服务以分享者的基础路径来访问文件</li>
 *   <li>通过文件服务执行具体的文件操作</li>
 *   <li>在 finally 块中清理分享访问上下文，避免上下文泄漏</li>
 * </ol>
 *
 * <h3>分享条目过滤机制</h3>
 * <p>
 * 当创建分享时指定了分享条目（shareEntries），访问根目录时只会显示被选中的文件和文件夹，
 * 而不是显示整个目录。进入子目录后则不受此限制。这是通过 {@link #getFilteredFileList}
 * 和 {@link #filterByShareEntries} 方法实现的。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Service
public class ShareLinkFileService {

    /**
     * 分享链接服务，用于验证分享链接有效性、获取分享信息、更新访问/下载次数
     */
    @Resource
    private ShareLinkService shareLinkService;

    /**
     * 文件处理责任链，用于对文件列表进行后处理（如权限过滤、密码验证等）
     */
    @Resource
    private FileChain fileChain;

    /**
     * 用户存储源权限服务，用于获取分享创建者在存储源上的权限映射
     */
    @Resource
    private UserStorageSourceService userStorageSourceService;

    /**
     * 获取分享文件列表
     * <p>
     * 这是分享文件浏览的核心方法。根据分享链接 key 和相对路径，返回该路径下的文件列表。
     * 在根目录时，如果分享设置了分享条目过滤，则只返回被选中的条目。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>验证分享链接有效性</li>
     *   <li>验证分享密码</li>
     *   <li>设置分享访问上下文</li>
     *   <li>获取过滤后的文件列表</li>
     *   <li>执行文件处理责任链</li>
     *   <li>构建分享链接信息并更新访问次数（仅根目录）</li>
     *   <li>清理分享访问上下文</li>
     * </ol>
     *
     * @param shareKey       分享链接 key，唯一标识一个分享
     * @param relativePath   相对路径，相对于分享根目录的路径，空字符串或 "/" 表示根目录
     * @param password       分享链接密码，如果分享未设置密码可为空
     * @param folderPassword 目录密码（与分享密码独立，用于加密的目录）
     * @param orderBy        排序字段
     * @param orderDirection 排序方向（asc / desc）
     * @return 分享文件信息结果，包含文件列表、路径信息、分享元数据等
     */
    public ShareFileInfoResult getShareFileList(String shareKey, String relativePath, String password, String folderPassword, String orderBy, String orderDirection) {
        // 第一步：获取有效的分享链接（会校验存在性和过期状态）
        ShareLink shareLink = getValidShareLink(shareKey);

        // 第二步：验证分享密码（如果分享设置了密码）
        validateSharePassword(shareLink, password);

        try {
            // 第三步：设置分享访问上下文
            // 关键：ShareAccessContext.setShareAccess 会让文件服务的 getCurrentUserBasePath() 返回分享的基础路径，
            // 而不是当前登录用户的基础路径，从而实现以分享者的视角访问文件
            ShareAccessContext.setShareAccess(shareKey, shareLink.getSharePath(), shareLink.getUserId());

            // 第四步：获取文件服务实例
            AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageKey(shareLink.getStorageKey());
            if (fileService == null) {
                throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
            }

            // 第五步：根据分享条目获取过滤后的文件列表
            // 根目录时可能只返回被选中的条目，子目录则返回全部
            List<FileItemResult> fileItemList = getFilteredFileList(shareLink, fileService, relativePath);

            // 第六步：执行文件处理责任链
            // 责任链会对文件列表进行一系列后处理，如密码验证、排序、权限过滤等
            FileListRequest fileListRequest = new FileListRequest();
            fileListRequest.setPath(relativePath);
            // 目录密码（与分享密码独立，用于加密目录）
            fileListRequest.setPassword(folderPassword);
            fileListRequest.setOrderBy(orderBy);
            fileListRequest.setOrderDirection(orderDirection);
            FileContext fileContext = FileContext.builder()
                    .storageId(fileService.getStorageId())
                    .fileListRequest(fileListRequest)
                    .fileItemList(fileItemList)
                    .fileService(fileService)
                    .operatorUserId(shareLink.getUserId())  // 使用分享创建者的用户 ID
                    .build();
            fileChain.execute(fileContext);

            // 第七步：构建分享链接信息
            ShareLinkResult shareLinkResult = shareLinkService.getShareLinkInfo(shareKey);

            // 更新访问次数（只在访问根路径时更新，避免每次进入子目录都计数）
            if (StrUtil.isBlank(relativePath) || "/".equals(relativePath)) {
                shareLinkService.incrementAccessCount(shareKey);
            }

            // 第八步：构建返回结果，附加分享创建者的权限映射
            ShareFileInfoResult shareFileInfoResult = new ShareFileInfoResult(fileContext.getFileItemList(), relativePath, shareLinkResult);
            Integer storageId = fileService.getStorageId();
            shareFileInfoResult.setPermission(userStorageSourceService.getPermissionMapByUserIdAndStorageId(shareLink.getUserId(), storageId));
            return shareFileInfoResult;

        } catch (BizException e) {
            // 业务异常直接抛出，不做包装
            throw e;
        } catch (Exception e) {
            // 其他异常包装为业务异常后抛出
            throw new BizException(ErrorCode.BIZ_SHARE_FILE_LIST_ERROR.setMessage(ErrorCode.BIZ_SHARE_FILE_LIST_ERROR.getMessage() + ": " + e.getMessage()));
        } finally {
            // 第九步：确保清理上下文，避免上下文泄漏到其他线程（特别是使用线程池时）
            ShareAccessContext.clear();
        }
    }

    /**
     * 获取分享文件的下载地址
     * <p>
     * 根据分享链接 key 和文件路径，返回该文件的下载地址。
     * 每次调用此方法都会更新该分享链接的下载次数。
     * </p>
     *
     * @param shareKey 分享链接 key
     * @param filePath 文件路径（相对于分享根目录）
     * @param password 分享密码
     * @return 文件的下载 URL
     * @throws BizException 当分享链接不存在、已过期、密码错误或存储源不存在时抛出
     */
    public String getShareFileDownloadUrl(String shareKey, String filePath, String password) {
        // 验证分享链接有效性
        ShareLink shareLink = getValidShareLink(shareKey);

        // 验证分享密码
        validateSharePassword(shareLink, password);

        try {
            // 获取文件服务实例
            AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageKey(shareLink.getStorageKey());
            if (fileService == null) {
                throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
            }

            // 更新下载次数（缓冲区模式，高频访问时不会每次都写数据库）
            shareLinkService.incrementDownloadCount(shareKey);

            // 获取下载地址
            // 将分享的绝对路径与文件相对路径拼接，得到完整的文件路径
            return fileService.getDownloadUrl(StringUtils.concat(shareLink.getSharePath(), filePath));

        } catch (Exception e) {
            throw new BizException(ErrorCode.BIZ_SHARE_FILE_DOWNLOAD_ERROR.setMessage(ErrorCode.BIZ_SHARE_FILE_DOWNLOAD_ERROR.getMessage() + ": " + e.getMessage()));
        } finally {
            // 清理分享访问上下文
            ShareAccessContext.clear();
        }
    }

    /**
     * 获取分享文件项信息
     * <p>
     * 获取单个文件或文件夹的详细信息（如大小、修改时间、类型等）。
     * 适用于查看文件详情页面。
     * </p>
     *
     * @param shareKey 分享链接 key
     * @param filePath 文件路径（相对于分享根目录）
     * @param password 分享密码
     * @return 文件项信息，包含名称、大小、类型、修改时间等
     * @throws BizException 当分享链接不存在、已过期、密码错误或存储源不存在时抛出
     */
    public FileItemResult getShareFileItem(String shareKey, String filePath, String password) {
        // 验证分享链接有效性
        ShareLink shareLink = getValidShareLink(shareKey);

        // 验证分享密码
        validateSharePassword(shareLink, password);

        try {
            // 设置分享访问上下文，使文件服务以分享者的基础路径来访问文件
            ShareAccessContext.setShareAccess(shareKey, shareLink.getSharePath(), shareLink.getUserId());

            // 获取文件服务实例
            AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageKey(shareLink.getStorageKey());
            if (fileService == null) {
                throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
            }

            // 获取文件项详细信息
            // getCurrentUserBasePath() 会返回分享的基础路径（因为上面设置了 ShareAccessContext）
            return fileService.getFileItem(filePath);

        } catch (Exception e) {
            throw new BizException(ErrorCode.BIZ_SHARE_FILE_INFO_ERROR.setMessage(ErrorCode.BIZ_SHARE_FILE_INFO_ERROR.getMessage() + ": " + e.getMessage()));
        } finally {
            // 清理分享访问上下文
            ShareAccessContext.clear();
        }
    }

    /**
     * 获取有效的分享链接
     * <p>
     * 委托给 {@link ShareLinkService#getValidShareLink(String)} 方法，
     * 该方法会校验分享链接是否存在以及是否已过期。
     * </p>
     *
     * @param shareKey 分享链接 key
     * @return 有效的分享链接实体
     * @throws BizException 当分享链接不存在或已过期时抛出
     */
    private ShareLink getValidShareLink(String shareKey) {
        return shareLinkService.getValidShareLink(shareKey);
    }

    /**
     * 验证分享密码
     * <p>
     * 只有当分享链接设置了密码时才进行验证。如果分享未设置密码，任何密码（包括空密码）都能通过。
     * 如果分享设置了密码，则要求输入的密码必须与分享密码完全匹配。
     * </p>
     *
     * @param shareLink 分享链接实体
     * @param password  访问者输入的密码
     * @throws BizException 当分享设置了密码但输入密码不匹配时抛出 {@link ErrorCode#BIZ_SHARE_PASSWORD_ERROR}
     */
    private void validateSharePassword(ShareLink shareLink, String password) {
        // 如果分享设置了密码，则需要验证
        if (StrUtil.isNotBlank(shareLink.getPassword())) {
            // 密码为空或不匹配则抛出异常
            if (StrUtil.isBlank(password) || !Objects.equals(shareLink.getPassword(), password)) {
                throw new BizException(ErrorCode.BIZ_SHARE_PASSWORD_ERROR);
            }
        }
    }


    /**
     * 根据分享条目获取过滤后的文件列表
     * <p>
     * 分发逻辑：如果当前路径是根目录，则需要进行分享条目过滤（只显示被选中的条目）；
     * 如果是子路径，则直接返回该路径下的所有文件，不做过滤。
     * </p>
     *
     * @param shareLink    分享链接实体，包含分享条目（shareItem）JSON 字符串
     * @param fileService  文件服务实例，用于获取文件列表
     * @param relativePath 当前访问的相对路径
     * @return 过滤后的文件列表
     * @throws Exception 文件服务可能抛出的异常
     */
    private List<FileItemResult> getFilteredFileList(ShareLink shareLink, AbstractBaseFileService<?> fileService, String relativePath) throws Exception {
        // 如果是访问根路径且指定了分享条目，需要进行过滤
        if (StrUtil.isBlank(relativePath) || "/".equals(relativePath)) {
            return getFilteredRootFileList(shareLink, fileService);
        }

        // 如果是访问子路径，直接返回该路径下的所有文件（不受分享条目限制）
        return fileService.fileList(relativePath);
    }

    /**
     * 获取过滤后的根路径文件列表
     * <p>
     * 首先获取分享根目录下的所有文件，然后根据分享条目进行过滤。
     * 如果分享没有指定分享条目（shareItem 为空），则返回所有文件。
     * </p>
     *
     * @param shareLink   分享链接实体
     * @param fileService 文件服务实例
     * @return 过滤后的根路径文件列表（只包含分享条目中指定的文件和文件夹）
     * @throws Exception 文件服务可能抛出的异常
     */
    private List<FileItemResult> getFilteredRootFileList(ShareLink shareLink, AbstractBaseFileService<?> fileService) throws Exception {
        // 获取分享路径下的所有文件
        List<FileItemResult> allFiles = fileService.fileList("/");

        // 解析分享条目（从 JSON 字符串反序列化为 ShareEntryDTO 列表）
        List<ShareEntryDTO> shareEntries = parseShareEntries(shareLink.getShareItem());

        // 如果没有指定分享条目，返回所有文件
        if (CollectionUtils.isEmpty(shareEntries)) {
            return allFiles;
        }

        // 根据分享条目过滤文件列表
        return filterByShareEntries(allFiles, shareEntries);
    }

    /**
     * 根据分享条目过滤文件列表
     * <p>
     * 将分享条目按类型分为文件夹集合和文件集合，然后从所有文件中筛选出
     * 名称匹配的条目。只有类型和名称都匹配的文件才会出现在结果中。
     * </p>
     *
     * @param allFiles      分享根目录下的所有文件列表
     * @param shareEntries  分享条目列表，指定了哪些文件和文件夹应该被展示
     * @return 过滤后的文件列表（只包含分享条目中指定的文件和文件夹）
     */
    private List<FileItemResult> filterByShareEntries(List<FileItemResult> allFiles, List<ShareEntryDTO> shareEntries) {
        if (CollectionUtils.isEmpty(shareEntries)) {
            return allFiles;
        }

        // 从分享条目中提取文件夹名称集合
        Set<String> folderNames = shareEntries.stream()
                .filter(entry -> entry.getType() == ShareEntryTypeEnum.FOLDER)
                .map(ShareEntryDTO::getName)
                .collect(Collectors.toSet());

        // 从分享条目中提取文件名称集合
        Set<String> fileNames = shareEntries.stream()
                .filter(entry -> entry.getType() == ShareEntryTypeEnum.FILE)
                .map(ShareEntryDTO::getName)
                .collect(Collectors.toSet());

        // 过滤：文件类型匹配文件集合，文件夹类型匹配文件夹集合
        return allFiles.stream()
                .filter(file -> {
                    if (file.getType() == FileTypeEnum.FOLDER) {
                        return folderNames.contains(file.getName());
                    }
                    return fileNames.contains(file.getName());
                })
                .collect(Collectors.toList());
    }

    /**
     * 解析分享条目 JSON 字符串
     * <p>
     * 将数据库中存储的 JSON 字符串（shareItem 字段）反序列化为 ShareEntryDTO 列表。
     * 如果 JSON 为空或解析失败，返回空列表。
     * </p>
     *
     * @param shareItemJson 分享条目的 JSON 字符串，格式如 [{"name":"file1.txt","type":"FILE"}, ...]
     * @return 分享条目列表，解析失败时返回空列表
     */
    private List<ShareEntryDTO> parseShareEntries(String shareItemJson) {
        if (StrUtil.isBlank(shareItemJson)) {
            return List.of();
        }

        try {
            // 使用 fastjson2 将 JSON 字符串反序列化为 ShareEntryDTO 列表
            return JSON.parseArray(shareItemJson, ShareEntryDTO.class);
        } catch (Exception e) {
            // 解析失败时返回空列表，不中断业务流程
            return List.of();
        }
    }

}

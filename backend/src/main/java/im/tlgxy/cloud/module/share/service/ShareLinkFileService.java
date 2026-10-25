package im.tlgxy.cloud.module.share.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.share.mapper.ShareLinkMapper;
import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.entity.ShareLink;
import im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum;
import im.tlgxy.cloud.module.share.model.result.ShareFileInfoResult;
import im.tlgxy.cloud.module.share.model.result.ShareLinkResult;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.chain.FileContext;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import im.tlgxy.cloud.module.user.service.UserStoragePermissionPolicy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Share reads enforce the same selected-entry and fresh authorization boundary on every route. */
@Service
public class ShareLinkFileService {
    @Resource private ShareLinkService shareLinkService;
    @Resource private ShareLinkMapper shareLinkMapper;
    @Resource private FileChain fileChain;
    @Resource private UserStorageSourceService userStorageSourceService;
    @Resource private UserStorageSourceMapper userStorageSourceMapper;
    @Resource private UserMapper userMapper;
    @Resource private StorageSourceMapper storageSourceMapper;
    @Resource private TenantMapper tenantMapper;

    public ShareFileInfoResult getShareFileList(String shareKey, String relativePath, String password,
            String folderPassword, String orderBy, String orderDirection) {
        ShareLink share = getValidShareLink(shareKey);
        validateSharePassword(share, password);
        String path = SharePathPolicy.normalize(relativePath);
        List<ShareEntryDTO> entries = parseShareEntries(share.getShareItem());
        SharePathPolicy.requireSelected(entries, path, true);
        UserStorageSource grant = validateCurrentAuthorization(share, false);
        try {
            ShareAccessContext.setShareAccess(shareKey, share.getSharePath(), share.getUserId());
            AbstractBaseFileService<?> files = requireFileService(share);
            List<FileItemResult> items = files.fileList(path);
            if ("/".equals(path) && !entries.isEmpty()) {
                items = items.stream().filter(item -> entries.stream().anyMatch(entry ->
                        Objects.equals(entry.getName(), item.getName())
                                && (item.getType() == FileTypeEnum.FOLDER
                                ? entry.getType() == ShareEntryTypeEnum.FOLDER
                                : entry.getType() == ShareEntryTypeEnum.FILE))).toList();
            }
            FileListRequest request = new FileListRequest();
            request.setPath(path);
            request.setPassword(folderPassword);
            request.setOrderBy(orderBy);
            request.setOrderDirection(orderDirection);
            FileContext context = FileContext.builder().storageId(files.getStorageId())
                    .fileListRequest(request).fileItemList(items).fileService(files)
                    .operatorUserId(share.getUserId()).build();
            fileChain.execute(context);
            // Provider presigned URLs cannot re-check a revoked share; all share reads use our proxy.
            for (FileItemResult item : context.getFileItemList()) {
                if (item.getType() != FileTypeEnum.FOLDER && item.getUrl() != null) {
                    item.setUrl(canReadContent(grant)
                            ? shareDownloadUrl(files, StringUtils.concat(share.getSharePath(), path, item.getName())) : null);
                }
            }
            ShareLinkResult info = shareLinkService.getShareLinkInfo(shareKey);
            if ("/".equals(path)) shareLinkService.incrementAccessCount(shareKey);
            ShareFileInfoResult result = new ShareFileInfoResult(context.getFileItemList(), path, info);
            result.setPermission(userStorageSourceService.getPermissionMapByUserIdAndStorageId(share.getUserId(), files.getStorageId()));
            return result;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.BIZ_SHARE_FILE_LIST_ERROR);
        } finally {
            ShareAccessContext.clear();
        }
    }

    public String getShareFileDownloadUrl(String shareKey, String filePath, String password) {
        return getShareFileDownloadUrl(shareKey, filePath, password, null);
    }

    public String getShareFileDownloadUrl(String shareKey, String filePath, String password, String folderPassword) {
        ShareLink share = getValidShareLink(shareKey);
        validateSharePassword(share, password);
        String path = SharePathPolicy.normalize(filePath);
        SharePathPolicy.requireSelected(parseShareEntries(share.getShareItem()), path, false);
        validateCurrentAuthorization(share, true);
        try {
            ShareAccessContext.setShareAccess(shareKey, share.getSharePath(), share.getUserId());
            AbstractBaseFileService<?> files = requireFileService(share);
            FileItemResult item = readVisibleItem(share, files, path, folderPassword);
            if (item == null || item.getType() == FileTypeEnum.FOLDER) {
                throw new BizException(ErrorCode.BIZ_SHARE_FILE_DOWNLOAD_ERROR);
            }
            String url = shareDownloadUrl(files, StringUtils.concat(share.getSharePath(), path));
            shareLinkService.incrementDownloadCount(shareKey);
            return url;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.BIZ_SHARE_FILE_DOWNLOAD_ERROR);
        } finally {
            ShareAccessContext.clear();
        }
    }

    public FileItemResult getShareFileItem(String shareKey, String filePath, String password) {
        return getShareFileItem(shareKey, filePath, password, null);
    }

    public FileItemResult getShareFileItem(String shareKey, String filePath, String password, String folderPassword) {
        ShareLink share = getValidShareLink(shareKey);
        validateSharePassword(share, password);
        String path = SharePathPolicy.normalize(filePath);
        SharePathPolicy.requireSelected(parseShareEntries(share.getShareItem()), path, false);
        UserStorageSource grant = validateCurrentAuthorization(share, false);
        try {
            ShareAccessContext.setShareAccess(shareKey, share.getSharePath(), share.getUserId());
            AbstractBaseFileService<?> files = requireFileService(share);
            FileItemResult item = readVisibleItem(share, files, path, folderPassword);
            if (item != null && item.getType() != FileTypeEnum.FOLDER) {
                item.setUrl(canReadContent(grant) ? shareDownloadUrl(files, StringUtils.concat(share.getSharePath(), path)) : null);
            }
            return item;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.BIZ_SHARE_FILE_INFO_ERROR);
        } finally {
            ShareAccessContext.clear();
        }
    }

    /** Called after authentication of the share-bound ticket, just before opening a stream. */
    public void validateSharedProxyDownload(String shareKey, String storageKey, String fullFilePath) {
        ShareLink share = getValidShareLink(shareKey);
        if (!Objects.equals(storageKey, share.getStorageKey())) deny();
        String base = SharePathPolicy.normalize(share.getSharePath());
        String full = SharePathPolicy.normalize(fullFilePath);
        if (!SharePathPolicy.isWithin(full, base) || full.equals(base)) deny();
        String relative = "/".equals(base) ? full : full.substring(base.length());
        SharePathPolicy.requireSelected(parseShareEntries(share.getShareItem()), relative, false);
        validateCurrentAuthorization(share, true);
    }

    private ShareLink getValidShareLink(String key) {
        // Read the database rather than an old cache when deletion/expiry is a revocation event.
        ShareLink share = shareLinkMapper.getByShareKey(key);
        if (share == null) throw new BizException(ErrorCode.BIZ_SHARE_LINK_NOT_EXIST);
        if (share.getExpireDate() != null && !share.getExpireDate().after(new Date())) {
            throw new BizException(ErrorCode.BIZ_SHARE_LINK_EXPIRED);
        }
        return share;
    }

    private UserStorageSource validateCurrentAuthorization(ShareLink share, boolean content) {
        User owner = share.getUserId() == null ? null : userMapper.selectById(share.getUserId());
        StorageSource storage = storageSourceMapper.findByStorageKey(share.getStorageKey());
        if (owner == null || !Boolean.TRUE.equals(owner.getEnable()) || storage == null
                || !Boolean.TRUE.equals(storage.getEnable()) || storage.getTenantId() == null) deny();
        Tenant tenant = tenantMapper.selectById(storage.getTenantId());
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())
                || (owner.getRole() != UserRoleEnum.ADMIN && !Objects.equals(owner.getTenantId(), storage.getTenantId()))) deny();
        UserStorageSource grant = UserStoragePermissionPolicy.effectiveGrant(owner, storage,
                userStorageSourceMapper.getByUserIdAndStorageId(owner.getId(), storage.getId()));
        if (grant == null || !Boolean.TRUE.equals(grant.getEnable()) || grant.getPermissions() == null
                || !grant.getPermissions().contains(FileOperatorTypeEnum.SHARE_LINK.getValue())) deny();
        String shareBase = SharePathPolicy.normalize(share.getSharePath());
        String allowedBase = SharePathPolicy.normalize(grant.getRootPath());
        if (!SharePathPolicy.isWithin(shareBase, allowedBase) || (content && !canReadContent(grant))) deny();
        return grant;
    }

    private boolean canReadContent(UserStorageSource grant) {
        return grant.getPermissions().contains(FileOperatorTypeEnum.DOWNLOAD.getValue())
                || grant.getPermissions().contains(FileOperatorTypeEnum.PREVIEW.getValue());
    }

    private AbstractBaseFileService<?> requireFileService(ShareLink share) {
        AbstractBaseFileService<?> files = StorageSourceContext.getByStorageKey(share.getStorageKey());
        if (files == null) throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
        return files;
    }

    private FileItemResult readVisibleItem(ShareLink share, AbstractBaseFileService<?> files, String path, String folderPassword) throws Exception {
        FileItemResult item = files.getFileItem(path);
        if (item == null) return null;
        FileListRequest request = new FileListRequest();
        request.setPath(FileUtils.getParentPath(path));
        request.setPassword(folderPassword);
        FileContext context = FileContext.builder().storageId(files.getStorageId()).fileListRequest(request)
                .fileItemList(new ArrayList<>(List.of(item))).fileService(files).operatorUserId(share.getUserId()).build();
        // Reuse folder-password, hidden-file and read-permission policy instead of bypassing the listing chain.
        fileChain.execute(context);
        if (context.getFileItemList().isEmpty()) deny();
        return context.getFileItemList().getFirst();
    }

    private String shareDownloadUrl(AbstractBaseFileService<?> files, String fullPath) {
        if (!(files instanceof AbstractProxyTransferService<?> proxy)) {
            throw new BizException(ErrorCode.BIZ_UNSUPPORTED_PROXY_DOWNLOAD);
        }
        return proxy.getProxyDownloadUrl(fullPath);
    }

    private void validateSharePassword(ShareLink share, String password) {
        if (StrUtil.isNotBlank(share.getPassword()) && !Objects.equals(share.getPassword(), password)) {
            throw new BizException(ErrorCode.BIZ_SHARE_PASSWORD_ERROR);
        }
    }

    static List<ShareEntryDTO> parseShareEntries(String value) {
        if (StrUtil.isBlank(value)) return List.of(); // Legacy whole-directory shares.
        try {
            List<ShareEntryDTO> entries = JSON.parseArray(value, ShareEntryDTO.class);
            if (entries == null) deny();
            for (ShareEntryDTO entry : entries) {
                if (entry == null || entry.getType() == null || StrUtil.isBlank(entry.getName())) deny();
                String name = SharePathPolicy.normalize(entry.getName());
                if (!name.equals("/" + entry.getName()) || name.substring(1).contains("/")) deny();
            }
            return entries;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            // Malformed selections must never become a whole-directory share.
            throw new BizException(ErrorCode.BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION);
        }
    }

    private static void deny() {
        throw new BizException(ErrorCode.BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION);
    }
}

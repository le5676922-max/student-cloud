package im.tlgxy.cloud.module.share.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.share.mapper.ShareLinkMapper;
import im.tlgxy.cloud.module.share.model.entity.ShareLink;
import im.tlgxy.cloud.module.storage.chain.FileChain;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShareLinkFileServiceTest {
    private final ShareLinkFileService service = new ShareLinkFileService();
    private final ShareLinkMapper shares = mock(ShareLinkMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final StorageSourceMapper storage = mock(StorageSourceMapper.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final FileChain chain = mock(FileChain.class);
    private final AbstractProxyTransferService<?> files = mock(AbstractProxyTransferService.class);
    private final ShareLink share = new ShareLink();
    private final User owner = new User();
    private final Tenant tenant = new Tenant();
    private final StorageSource source = new StorageSource();
    private final UserStorageSource grant = new UserStorageSource();
    private MockedStatic<StorageSourceContext> context;

    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(service, "shareLinkMapper", shares);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "userStorageSourceMapper", grants);
        ReflectionTestUtils.setField(service, "storageSourceMapper", storage);
        ReflectionTestUtils.setField(service, "tenantMapper", tenants);
        ReflectionTestUtils.setField(service, "fileChain", chain);
        share.setShareKey("synthetic"); share.setUserId(20); share.setStorageKey("workspace"); share.setSharePath("/allowed");
        share.setShareItem("[{\"name\":\"selected.txt\",\"type\":\"FILE\"}]");
        owner.setId(20); owner.setRole(UserRoleEnum.USER); owner.setTenantId(10); owner.setEnable(true);
        tenant.setId(10); tenant.setEnable(true);
        source.setId(30); source.setTenantId(10); source.setEnable(true);
        grant.setEnable(true); grant.setRootPath("/"); grant.setPermissions(Set.of("createShareLink", "download", "preview"));
        when(shares.getByShareKey("synthetic")).thenReturn(share);
        when(users.selectById(20)).thenReturn(owner);
        when(storage.findByStorageKey("workspace")).thenReturn(source);
        when(tenants.selectById(10)).thenReturn(tenant);
        when(grants.getByUserIdAndStorageId(20, 30)).thenReturn(grant);
        when(files.getStorageId()).thenReturn(30);
        FileItemResult item = new FileItemResult(); item.setName("selected.txt"); item.setType(FileTypeEnum.FILE);
        when(files.getFileItem("/selected.txt")).thenReturn(item);
        when(files.getProxyDownloadUrl(anyString())).thenAnswer(call -> "synthetic-proxy");
        context = mockStatic(StorageSourceContext.class);
        context.when(() -> StorageSourceContext.getByStorageKey("workspace")).thenReturn(files);
        ReflectionTestUtils.setField(service, "shareLinkService", mock(ShareLinkService.class));
    }

    @AfterEach void cleanup() { context.close(); ShareAccessContext.clear(); }

    @Test void downloadUsesShareContextAndCommonFileChainThenAlwaysClearsContext() throws Exception {
        when(files.getProxyDownloadUrl("/allowed/selected.txt")).thenAnswer(call -> {
            assertEquals("synthetic", ShareAccessContext.getShareKey());
            assertEquals(20, ShareAccessContext.getShareUserId());
            return "synthetic-proxy";
        });
        assertEquals("synthetic-proxy", service.getShareFileDownloadUrl("synthetic", "selected.txt", null));
        verify(chain).execute(any());
        assertFalse(ShareAccessContext.isShareAccess());
    }

    @Test void unselectedPathsAreDeniedAcrossListItemAndDownloadBeforeStorageAccess() {
        assertThrows(BizException.class, () -> service.getShareFileList("synthetic", "/hidden", null, null, null, null));
        assertThrows(BizException.class, () -> service.getShareFileItem("synthetic", "/unselected.txt", null));
        assertThrows(BizException.class, () -> service.getShareFileDownloadUrl("synthetic", "/unselected.txt", null));
        context.verifyNoInteractions(); verifyNoInteractions(files);
    }

    @Test void traversalIsDeniedBeforeAnySignatureGeneration() {
        for (String path : List.of("../other.txt", "%252e%252e/other.txt", "a\\other.txt")) {
            assertThrows(BizException.class, () -> service.getShareFileDownloadUrl("synthetic", path, null));
        }
        context.verifyNoInteractions(); verifyNoInteractions(files);
    }

    @Test void disablingOwnerOrWorkspaceRevokesAlreadyIssuedTicketAuthorization() {
        service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt");
        owner.setEnable(false);
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
        owner.setEnable(true); tenant.setEnable(false);
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
        tenant.setEnable(true); source.setEnable(false);
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
    }

    @Test void revokingGrantSharePermissionReadPermissionOrRootImmediatelyDeniesDownload() {
        grant.setEnable(false); assertDownloadDenied();
        grant.setEnable(true); grant.setPermissions(Set.of("preview", "download")); assertDownloadDenied();
        grant.setPermissions(Set.of("createShareLink")); assertDownloadDenied();
        grant.setPermissions(Set.of("createShareLink", "download")); grant.setRootPath("/different"); assertDownloadDenied();
    }

    @Test void deletedExpiredWrongStorageAndChangedScopeRevokeExistingTicket() {
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "different", "/allowed/selected.txt"));
        share.setShareItem("[{\"name\":\"other.txt\",\"type\":\"FILE\"}]");
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
        share.setExpireDate(new Date(System.currentTimeMillis() - 1));
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
        when(shares.getByShareKey("synthetic")).thenReturn(null);
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
    }

    @Test void corruptSelectionNeverExposesWholeDirectory() {
        share.setShareItem("broken");
        assertThrows(BizException.class, () -> service.getShareFileList("synthetic", "/", null, null, null, null));
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
        context.verifyNoInteractions();
    }

    @Test void selectedFolderDescendantsAreValidButSimilarSiblingIsNot() {
        share.setShareItem("[{\"name\":\"selected\",\"type\":\"FOLDER\"}]");
        assertDoesNotThrow(() -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected/sub/file.txt"));
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected-other/file.txt"));
    }

    private void assertDownloadDenied() {
        assertThrows(BizException.class, () -> service.validateSharedProxyDownload("synthetic", "workspace", "/allowed/selected.txt"));
        assertThrows(BizException.class, () -> service.getShareFileDownloadUrl("synthetic", "selected.txt", null));
    }
}

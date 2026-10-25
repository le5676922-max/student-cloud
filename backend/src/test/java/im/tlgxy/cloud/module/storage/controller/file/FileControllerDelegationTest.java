package im.tlgxy.cloud.module.storage.controller.file;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.application.DirectoryListingApplicationService;
import im.tlgxy.cloud.module.storage.application.port.StorageDriverRegistry;
import im.tlgxy.cloud.module.storage.convert.StorageSourceConvert;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.request.base.FileItemRequest;
import im.tlgxy.cloud.module.storage.model.request.base.FileListRequest;
import im.tlgxy.cloud.module.storage.model.result.FileInfoResult;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceResult;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileControllerDelegationTest {
    private final StorageSourceService sources = mock(StorageSourceService.class);
    private final StorageSourceConvert conversion = mock(StorageSourceConvert.class);
    private final DirectoryListingApplicationService listing = mock(DirectoryListingApplicationService.class);
    private final StorageDriverRegistry drivers = mock(StorageDriverRegistry.class);
    private final FileController controller = new FileController(sources, conversion, listing, drivers);

    @Test
    void directoryEndpointForwardsCurrentActorAndPreservesAjaxResponse() throws Exception {
        var request = new FileListRequest();
        request.setStorageKey("source");
        var page = new FileInfoResult(List.of(), "protected-folder-pattern");
        when(listing.list(request, 73)).thenReturn(page);
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(73);
            var result = controller.list(request);
            assertEquals(AjaxJson.CODE_SUCCESS, result.getCode());
            assertEquals("ok", result.getMsg());
            assertSame(page, result.getData());
            verify(listing).list(request, 73);
            verifyNoMoreInteractions(listing);
            verifyNoInteractions(sources, conversion, drivers);
        }
    }

    @Test
    void applicationFailureCannotBeWrappedAsAFalseSuccessfulResponse() throws Exception {
        var request = new FileListRequest();
        var denied = new BizException("权限已撤销");
        when(listing.list(request, 73)).thenThrow(denied);
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(73);
            assertSame(denied, assertThrows(BizException.class, () -> controller.list(request)));
            verifyNoInteractions(sources, conversion, drivers);
        }
    }

    @Test
    void storageSourceEndpointKeepsActorScopedQueryAndOriginalResultType() {
        List<StorageSource> entities = List.of(new StorageSource());
        List<StorageSourceResult> results = List.of(new StorageSourceResult());
        when(sources.findAllEnableOrderByOrderNum(73)).thenReturn(entities);
        when(conversion.entityToResultList(entities)).thenReturn(results);
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(73);
            var response = controller.storageList();
            assertEquals(AjaxJson.CODE_SUCCESS, response.getCode());
            assertSame(results, response.getData());
            verifyNoInteractions(listing, drivers);
        }
    }

    @Test
    void fileItemUsesTheRegistryPortWithoutChangingRequestDefaultsOrDataWrapper() throws Exception {
        var request = new FileItemRequest();
        request.setStorageKey("source");
        when(sources.findIdByKey("source")).thenReturn(2);
        AbstractBaseFileService<?> driver = mock(AbstractBaseFileService.class);
        doReturn(driver).when(drivers).getByStorageId(2);
        var item = new FileItemResult();
        when(driver.getFileItem("/")).thenReturn(item);

        var response = controller.fileItem(request);

        assertEquals("/", request.getPath());
        assertEquals(AjaxJson.CODE_SUCCESS, response.getCode());
        assertSame(item, response.getData());
        verify(drivers).getByStorageId(2);
        verify(driver).getFileItem("/");
        verifyNoInteractions(listing, conversion);
    }
}

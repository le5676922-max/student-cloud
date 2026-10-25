package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.module.storage.model.param.S3Param;
import im.tlgxy.cloud.module.storage.service.impl.S3ServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class S3DirectoryMetadataTest {
    @Test void nativePageUsesOneBoundedListRequestAndNoSignedUrlsOrPaginator() {
        var driver = spy(new S3ServiceImpl()); var params = new S3Param();
        ReflectionTestUtils.setField(params, "bucketName", "synthetic-bucket"); ReflectionTestUtils.setField(params, "basePath", "/base");
        ReflectionTestUtils.setField(driver, "param", params); var client = mock(S3Client.class);
        ReflectionTestUtils.setField(driver, "s3ClientNew", client);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("base/own/docs/a.txt").size(1L).lastModified(Instant.EPOCH).build())
                .commonPrefixes(CommonPrefix.builder().prefix("base/own/docs/folder/").build())
                .isTruncated(true).nextContinuationToken("opaque-next").build());
        var result = driver.nativeDirectoryPage("/docs/", "/own", 2, "opaque-current");
        assertEquals(2, result.files().size()); assertEquals(2, result.rawItemCount()); assertEquals("opaque-next", result.nextProviderToken());
        assertTrue(result.files().stream().allMatch(file -> file.getUrl() == null));
        verify(driver, never()).getDownloadUrl(anyString()); verify(client, never()).listObjectsV2Paginator(any(ListObjectsV2Request.class));
        var request = org.mockito.ArgumentCaptor.forClass(ListObjectsV2Request.class); verify(client).listObjectsV2(request.capture());
        assertEquals(2, request.getValue().maxKeys()); assertEquals("base/own/docs/", request.getValue().prefix());
        assertEquals("opaque-current", request.getValue().continuationToken()); assertEquals("/", request.getValue().delimiter());
    }

    @Test void rawPageCountIncludesDiscardedDirectoryPlaceholderForCallerBudgets() {
        var driver = spy(new S3ServiceImpl()); var params = new S3Param();
        ReflectionTestUtils.setField(params, "bucketName", "synthetic-bucket"); ReflectionTestUtils.setField(params, "basePath", "/base");
        ReflectionTestUtils.setField(driver, "param", params); var client = mock(S3Client.class);
        ReflectionTestUtils.setField(driver, "s3ClientNew", client);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("base/own/docs/").build(),
                        S3Object.builder().key("base/own/docs/a.txt").size(1L).build())
                .isTruncated(false).build());
        var result = driver.nativeDirectoryPage("/docs/", "/own", 2, null);
        assertEquals(1, result.files().size()); assertEquals(2, result.rawItemCount());
        assertNull(result.nextProviderToken()); verify(driver, never()).getDownloadUrl(anyString());
    }

    @Test void malformedProviderCannotListOutsideAuthorizedRootOrExceedRequestedPage() {
        var driver = new S3ServiceImpl(); var params = new S3Param();
        ReflectionTestUtils.setField(params, "bucketName", "synthetic-bucket"); ReflectionTestUtils.setField(params, "basePath", "/base");
        ReflectionTestUtils.setField(driver, "param", params); var client = mock(S3Client.class);
        ReflectionTestUtils.setField(driver, "s3ClientNew", client);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("another/root/private.txt").build()).build());
        assertThrows(im.tlgxy.cloud.core.exception.core.BizException.class, () -> driver.nativeDirectoryPage("/docs/", "/own", 2, null));
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .isTruncated(true).build());
        assertThrows(im.tlgxy.cloud.core.exception.core.BizException.class, () -> driver.nativeDirectoryPage("/docs/", "/own", 2, null));
    }
    @Test void singleResponseWalkReadsMetadataOnlyThenSignsOnlyRequestedPage() {
        var driver = spy(new S3ServiceImpl()); var params = new S3Param();
        ReflectionTestUtils.setField(params, "bucketName", "synthetic-bucket"); ReflectionTestUtils.setField(params, "basePath", "/base");
        ReflectionTestUtils.setField(driver, "param", params); var client = mock(S3Client.class);
        ReflectionTestUtils.setField(driver, "s3ClientNew", client); var pages = mock(ListObjectsV2Iterable.class);
        var response = ListObjectsV2Response.builder().contents(
                S3Object.builder().key("base/own/docs/a.txt").size(1L).lastModified(Instant.EPOCH).build(),
                S3Object.builder().key("base/own/docs/b.txt").size(2L).lastModified(Instant.EPOCH).build())
                .commonPrefixes(CommonPrefix.builder().prefix("base/own/docs/folder/").build()).build();
        when(pages.iterator()).thenAnswer(call -> List.of(response).iterator());
        when(client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(pages);
        doReturn("synthetic-url").when(driver).getDownloadUrl(anyString());
        var metadata = driver.directoryMetadata("/docs/", "/own"); assertEquals(3, metadata.size());
        assertTrue(metadata.stream().allMatch(file -> file.getUrl() == null)); verify(driver, never()).getDownloadUrl(anyString());
        verify(pages, times(1)).iterator(); verify(pages, never()).contents(); verify(pages, never()).commonPrefixes();
        driver.hydrateDirectoryPage(metadata.subList(0, 1), "/own");
        verify(driver).getDownloadUrl("/own/docs/a.txt"); assertNull(metadata.get(1).getUrl());
        var request = org.mockito.ArgumentCaptor.forClass(ListObjectsV2Request.class); verify(client).listObjectsV2Paginator(request.capture());
        assertEquals("base/own/docs/", request.getValue().prefix());
    }
}

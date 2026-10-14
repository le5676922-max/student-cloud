package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import java.util.List;
import im.tlgxy.cloud.core.exception.core.BizException;

/** Metadata only: no grants, passwords, credentials or signed URLs. Caller must authorize first. */
public interface DirectoryMetadataProvider {
    List<FileItemResult> directoryMetadata(String directory, String authorizedUserRoot);
    void hydrateDirectoryPage(List<FileItemResult> page, String authorizedUserRoot);

    default boolean supportsNativePagination() { return false; }

    /** One remote request only. The opaque provider token must remain on the server. */
    default NativePage nativeDirectoryPage(String directory, String authorizedUserRoot, int pageSize, String providerToken) {
        throw new BizException("此存储源暂不支持快速分页，请切换普通浏览");
    }

    record NativePage(List<FileItemResult> files, String nextProviderToken) {
        public NativePage { files = List.copyOf(files); }
    }
}

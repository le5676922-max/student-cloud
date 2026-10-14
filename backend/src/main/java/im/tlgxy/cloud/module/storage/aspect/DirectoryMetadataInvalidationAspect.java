package im.tlgxy.cloud.module.storage.aspect;

import im.tlgxy.cloud.module.storage.service.DirectoryMetadataCache;
import jakarta.annotation.Resource;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Invalidate even on partial/failed writes. Direct cloud upload completion also explicitly refreshes the frontend. */
@Aspect
@Component
public class DirectoryMetadataInvalidationAspect {
    @Resource private DirectoryMetadataCache cache;
    @After("execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.newFolder(..)) || "
            + "execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.delete*(..)) || "
            + "execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.rename*(..)) || "
            + "execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.move*(..)) || "
            + "execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.copy*(..)) || "
            + "execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.getUploadUrl(..)) || "
            + "execution(public * im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService.uploadFile(..))")
    public void afterMutation() { cache.invalidate(); }
}

package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.param.LocalParam;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.IOException;

/** Create only a just-approved member directory, never a user-selected absolute server path. */
@Component
public class MemberDirectoryProvisioner {
    public void prepare(int storage, int user, String root) {
        if (!MemberStoragePaths.personal(root, user)) throw new BizException("需要独立的成员个人目录");
        var driver = StorageSourceContext.getByStorageId(storage);
        if (!(driver.getParam() instanceof LocalParam local)) return;
        prepareLocal(Path.of(local.getFilePath()), root);
    }
    static void prepareLocal(Path base, String root) {
        Path absolute = base.toAbsolutePath().normalize();
        Path target = absolute.resolve(MemberStoragePaths.normalize(root).substring(1)).normalize();
        if (!target.startsWith(absolute)) throw new BizException("个人目录超出存储范围");
        try {
            Path current = absolute.getRoot();
            for (Path segment : target) {
                current = current.resolve(segment);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (!current.startsWith(absolute)) throw new IOException("Storage root missing");
                    try { Files.createDirectory(current); } catch (FileAlreadyExistsException raced) { /* Check below. */ }
                }
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe member directory");
            }
        } catch (IOException | SecurityException failure) { throw new BizException("无法安全创建成员目录，请检查存储源路径和读写权限"); }
    }
}

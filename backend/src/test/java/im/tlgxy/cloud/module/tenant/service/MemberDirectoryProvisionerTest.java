package im.tlgxy.cloud.module.tenant.service;
import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MemberDirectoryProvisionerTest {
    @TempDir Path root;
    @Test void createsOnlyTheApprovedSubdirectoryAndPreservesExistingFiles() throws Exception {
        Path base=Files.createDirectory(root.resolve("cloud"));Files.writeString(base.resolve("keep.txt"),"keep");
        MemberDirectoryProvisioner.prepareLocal(base,"/members/u10/course");
        assertTrue(Files.isDirectory(base.resolve("members/u10/course")));assertEquals("keep",Files.readString(base.resolve("keep.txt")));
        assertThrows(BizException.class,()->MemberDirectoryProvisioner.prepareLocal(base,"/../outside"));
        assertFalse(Files.exists(root.resolve("outside")));
    }
    @Test void aFileOrSymlinkIsNeverReplacedWithAPersonalDirectory() throws Exception {
        Path base=Files.createDirectory(root.resolve("cloud"));Files.writeString(base.resolve("members"),"keep");
        assertThrows(BizException.class,()->MemberDirectoryProvisioner.prepareLocal(base,"/members/u10"));assertEquals("keep",Files.readString(base.resolve("members")));
        Path other=Files.createDirectory(root.resolve("other"));
        try{Files.createSymbolicLink(other.resolve("members"),root);}catch(java.io.IOException|UnsupportedOperationException unavailable){return;}
        assertThrows(BizException.class,()->MemberDirectoryProvisioner.prepareLocal(other,"/members/u10"));
    }
}

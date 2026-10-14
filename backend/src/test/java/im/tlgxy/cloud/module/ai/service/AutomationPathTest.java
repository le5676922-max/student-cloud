package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AutomationPathTest {
    @ParameterizedTest
    @ValueSource(strings = {"../secret", "/a/../b", "/a/./b", "/a\\..\\b", "C:\\files", "/%2e%2e/secret", "/file.txt:stream", "/bad\nname"})
    void rejectsTraversalEncodedPathsAndWindowsAlternateStreams(String path) {
        assertThrows(BizException.class, () -> AutomationFileService.path(path));
    }
    @Test void normalizesOnlySeparators() {
        assertEquals("/学习/笔记.txt", AutomationFileService.path("//学习\\笔记.txt/"));
        assertEquals("/", AutomationFileService.path("/"));
        assertThrows(BizException.class, () -> AutomationFileService.path(null));
        assertThrows(BizException.class, () -> AutomationFileService.path("a".repeat(501)));
    }
    @Test void confinesNonexistentTargetsWithoutNeedingToCreateThem(@TempDir Path temp) throws Exception {
        Path root = temp.toRealPath();
        assertEquals(root.resolve("文档/test.txt"), AutomationFileService.confined(root, "文档/test.txt"));
        assertFalse(Files.exists(root.resolve("文档")));
        assertThrows(BizException.class, () -> AutomationFileService.confined(root, "../outside.txt"));
    }

    @Test void rejectsRedirectedDirectoriesEvenInsideTheSameStorageRoot(@TempDir Path temp) throws Exception {
        Path root = temp.toRealPath();
        Path privateDirectory = Files.createDirectory(root.resolve("private"));
        Files.writeString(privateDirectory.resolve("secret.txt"), "synthetic private data");
        Path link = root.resolve("alias");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            // Junction creation needs no symlink privilege. Both paths are confined to this disposable test directory.
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), privateDirectory.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } else Files.createSymbolicLink(link, privateDirectory);
        assertEquals(privateDirectory, link.toRealPath());
        assertThrows(BizException.class, () -> AutomationFileService.confined(root, "alias/secret.txt"));
        assertThrows(BizException.class, () -> AutomationFileService.confined(root, "alias/new.txt"));
        assertTrue(Files.exists(privateDirectory.resolve("secret.txt")));
    }
}

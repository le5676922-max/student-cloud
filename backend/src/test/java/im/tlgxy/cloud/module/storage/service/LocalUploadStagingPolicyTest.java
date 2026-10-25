package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalUploadStagingPolicyTest {
    @TempDir Path temporary;

    private LocalUploadStagingPolicy policy(Path staging, boolean enabled) {
        var properties = new ResumableUploadProperties();
        properties.setStagingRoot(staging.toString()); properties.setEnabled(enabled);
        return new LocalUploadStagingPolicy(properties);
    }

    @Test void independentMissingRootsPassWithoutCreatingAnything() throws Exception {
        var staging = temporary.resolve("private/parts"); var cloud = temporary.resolve("cloud/files");
        assertDoesNotThrow(() -> policy(staging, true).assertSeparated(cloud.toString()));
        assertFalse(Files.exists(staging)); assertFalse(Files.exists(cloud));
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void sameRootAndBothContainmentDirectionsAreRejectedEvenIfDisabled() throws Exception {
        var staging = Files.createDirectory(temporary.resolve("private"));
        Files.writeString(staging.resolve("existing-private-part"), "synthetic-part");
        var policy = policy(staging, false);
        assertThrows(BizException.class, () -> policy.assertSeparated(staging));
        assertThrows(BizException.class, () -> policy.assertSeparated(temporary));
        assertThrows(BizException.class, () -> policy.assertSeparated(staging.resolve("public/subtree")));
        assertEquals("synthetic-part", Files.readString(staging.resolve("existing-private-part")));
    }

    @Test void siblingWithSimilarPrefixIsNotContainment() {
        var staging = temporary.resolve("private");
        assertDoesNotThrow(() -> policy(staging, true).assertSeparated(temporary.resolve("private-files")));
    }

    @Test void courseMaterialStoreIsNeverExposedThroughLocalCloudInEitherContainmentDirection() {
        var properties = new ResumableUploadProperties(); properties.setStagingRoot(temporary.resolve("upload-staging").toString());
        Path courses = temporary.resolve("private-courses");
        var policy = new LocalUploadStagingPolicy(properties, courses.toString());
        for (Path publicRoot : java.util.List.of(courses, courses.resolve("assets"), temporary))
            assertThrows(BizException.class, () -> policy.assertSeparated(publicRoot));
        assertDoesNotThrow(() -> policy.assertSeparated(temporary.resolve("workspace-cloud")));
        assertFalse(Files.exists(courses));
    }

    @Test void relativeTraversalNetworkShareAndNullPathsFailClosed() {
        var policy = policy(temporary.resolve("private"), true);
        for (var value : new String[]{"relative", temporary.resolve("..").toString(), "\\\\host\\share", "//host/share", "bad\npath"})
            assertThrows(BizException.class, () -> policy.assertSeparated(value));
        assertThrows(BizException.class, () -> policy.assertSeparated((String) null));
        var properties = new ResumableUploadProperties(); properties.setStagingRoot(null);
        assertThrows(BizException.class, () -> new LocalUploadStagingPolicy(properties).assertSeparated(temporary));
    }

    @Test void symlinkAliasToPrivateRootOrIndependentDirectoryIsRejected() throws Exception {
        var staging = Files.createDirectory(temporary.resolve("private"));
        var alias = temporary.resolve("alias");
        try { Files.createSymbolicLink(alias, staging); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic-link creation is unavailable on this runner"); return;
        }
        var policy = policy(staging, true);
        assertThrows(BizException.class, () -> policy.assertSeparated(alias.resolve("public")));
        assertThrows(BizException.class, () -> policy(alias.resolve("future"), true).assertSeparated(temporary.resolve("cloud")));
        assertTrue(Files.isSymbolicLink(alias));
    }
}

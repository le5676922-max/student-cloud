package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.biz.InitializeStorageSourceBizException;
import im.tlgxy.cloud.module.storage.model.param.LocalParam;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageStagingInitializationTest {
    @TempDir Path temporary;

    private LocalServiceImpl driver(Path cloud, Path staging) {
        var properties = new ResumableUploadProperties(); properties.setEnabled(false); properties.setStagingRoot(staging.toString());
        var service = new LocalServiceImpl();
        ReflectionTestUtils.setField(service, "uploadStagingPolicy", new LocalUploadStagingPolicy(properties));
        return service;
    }

    private LocalParam parameters(Path root) {
        var parameters = new LocalParam(); ReflectionTestUtils.setField(parameters, "filePath", root.toString()); return parameters;
    }

    @Test void legacyRootCannotPublishPrivatePartsEvenWhenResumableIsDisabled() throws Exception {
        var staging = Files.createDirectory(temporary.resolve("private-parts"));
        var child = Files.createDirectory(staging.resolve("nested-cloud"));
        Files.writeString(staging.resolve("existing-private-part"), "synthetic-private-part");
        for (Path cloud : List.of(temporary, staging, child)) {
            var service = driver(cloud, staging);
            var rejected = assertThrows(InitializeStorageSourceBizException.class,
                    () -> service.init("Synthetic legacy root", 42, parameters(cloud)));
            assertEquals(42, rejected.getStorageId()); assertFalse(service.isInitialized());
            assertTrue(rejected.getMessage().contains("暂存"));
        }
        assertEquals("synthetic-private-part", Files.readString(staging.resolve("existing-private-part")));
    }

    @Test void independentlyConfiguredRootInitializesWithoutGlobalContextOrReadingParts() throws Exception {
        var cloud = Files.createDirectory(temporary.resolve("cloud")); var staging = Files.createDirectory(temporary.resolve("private"));
        Files.writeString(staging.resolve("part"), "synthetic-private-part");
        var service = driver(cloud, staging);
        assertDoesNotThrow(() -> service.init("Synthetic independent root", 43, parameters(cloud)));
        assertFalse(service.isInitialized()); // Only the existing authenticated connection check may set this flag.
        try (var entries = Files.list(cloud)) { assertEquals(0, entries.count()); }
    }

    @Test void directNewInstanceRetainsSecureDefaultWithoutSpring() {
        var service = new LocalServiceImpl();
        assertDoesNotThrow(() -> service.init("Synthetic direct root", 44, parameters(temporary)));
    }

    @Test void missingRootIsNeverCreatedWhileValidatingBoundary() {
        var missing = temporary.resolve("missing-cloud"); var service = driver(missing, temporary.resolve("private"));
        assertThrows(InitializeStorageSourceBizException.class, () -> service.init("Synthetic missing root", 45, parameters(missing)));
        assertFalse(Files.exists(missing)); assertFalse(Files.exists(temporary.resolve("private")));
    }
}

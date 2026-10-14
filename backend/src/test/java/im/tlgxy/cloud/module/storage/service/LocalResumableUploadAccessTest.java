package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.password.model.dto.VerifyResultDTO;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceConfigMapper;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.LocalParam;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalResumableUploadAccessTest {
    @TempDir Path work;
    Path cloud;
    StorageSourceMapper sources;
    StorageSourceConfigMapper configs;
    DirectoryAccessGuard grants;
    FilterConfigService filters;
    PasswordConfigService passwords;
    LocalServiceImpl driver;
    LocalParam parameters;
    LocalResumableUploadAccess service;

    @BeforeEach
    void setup() throws Exception {
        cloud = Files.createDirectory(work.resolve("cloud")).toRealPath();
        Files.createDirectory(cloud.resolve("tenant"));
        sources = mock(StorageSourceMapper.class); configs = mock(StorageSourceConfigMapper.class);
        grants = mock(DirectoryAccessGuard.class); filters = mock(FilterConfigService.class); passwords = mock(PasswordConfigService.class);
        driver = mock(LocalServiceImpl.class); parameters = mock(LocalParam.class);
        when(driver.getCurrentUserBasePath()).thenReturn("/tenant"); when(driver.getParam()).thenReturn(parameters);
        when(parameters.getFilePath()).thenReturn(cloud.toString());
        when(sources.findByStorageKey("cloud")).thenReturn(source(9, true));
        when(sources.findByType(StorageTypeEnum.LOCAL)).thenReturn(List.of(source(9, true)));
        when(configs.findByStorageIdOrderById(9)).thenReturn(List.of(config(cloud)));
        when(grants.require(7, 9, "/tenant")).thenReturn(new DirectoryAccessGuard.Grant("/tenant", Set.of("upload", "newFolder")));
        when(passwords.verifyPassword(eq(9), anyString(), nullable(String.class))).thenReturn(VerifyResultDTO.success());
        service = new LocalResumableUploadAccess(sources, configs, grants, filters, passwords);
    }

    @Test
    void checksFreshGrantHiddenRulesAndPasswordAtRelativeAndFullAncestors() throws Exception {
        try (var context = mockStatic(StorageSourceContext.class)) {
            context.when(() -> StorageSourceContext.getByStorageId(9)).thenAnswer(ignored -> driver);
            var access = service.require(7, "cloud", "/docs/", "报告.txt", "中文+百分%密码");
            assertEquals(cloud.resolve("tenant/docs/报告.txt"), access.target());
            assertTrue(access.local()); assertTrue(access.canCreateFolder()); assertEquals(64, access.rootFingerprint().length());
            verify(grants).require(7, 9, "/tenant");
            for (String path : List.of("/docs/报告.txt", "/docs/报告.txt/", "/docs", "/docs/", "/tenant/docs/报告.txt", "/tenant", "/"))
                verify(passwords).verifyPassword(9, path, "中文+百分%密码");
            when(filters.checkFileIsHidden(9, "/tenant/docs")).thenReturn(true);
            assertThrows(BizException.class, () -> service.require(7, "cloud", "/docs", "报告.txt", "中文+百分%密码"));
        }
    }

    @Test
    void passwordRejectionOrUploadPermissionRemovalCannotBeBypassed() {
        try (var context = mockStatic(StorageSourceContext.class)) {
            context.when(() -> StorageSourceContext.getByStorageId(9)).thenAnswer(ignored -> driver);
            when(passwords.verifyPassword(9, "/docs", null)).thenReturn(new VerifyResultDTO());
            assertThrows(BizException.class, () -> service.require(7, "cloud", "/docs", "data.bin", null));
            when(grants.require(7, 9, "/tenant")).thenReturn(new DirectoryAccessGuard.Grant("/tenant", Set.of("download")));
            assertThrows(BizException.class, () -> service.require(7, "cloud", "/", "data.bin", "any"));
        }
    }

    @Test
    void freshConfiguredRootMustMatchDriverAndReplacementHasADifferentFingerprint() throws Exception {
        try (var context = mockStatic(StorageSourceContext.class)) {
            context.when(() -> StorageSourceContext.getByStorageId(9)).thenAnswer(ignored -> driver);
            String original = service.require(7, "cloud", "/", "data.bin", null).rootFingerprint();
            Path second = Files.createDirectory(work.resolve("second-cloud")); Files.createDirectory(second.resolve("tenant"));
            when(configs.findByStorageIdOrderById(9)).thenReturn(List.of(config(second)));
            assertThrows(BizException.class, () -> service.require(7, "cloud", "/", "data.bin", null), "A stale cached driver cannot remain upload authority");
            when(parameters.getFilePath()).thenReturn(second.toString());
            assertNotEquals(original, service.require(7, "cloud", "/", "data.bin", null).rootFingerprint());
        }
    }

    @Test
    void allLocalRootsIncludingDisabledSourcesMustStayOutsidePrivateStaging() throws Exception {
        Path staging = work.resolve("private-staging");
        service.requirePrivateStaging(staging);
        when(sources.findByType(StorageTypeEnum.LOCAL)).thenReturn(List.of(source(9, true), source(10, false)));
        when(configs.findByStorageIdOrderById(10)).thenReturn(List.of(config(work)));
        assertThrows(BizException.class, () -> service.requirePrivateStaging(staging), "A disabled wider local source would expose staged chunks when enabled");
        when(configs.findByStorageIdOrderById(10)).thenReturn(List.of(config(staging.resolve("another-cloud-root"))));
        assertThrows(BizException.class, () -> service.requirePrivateStaging(staging));
        assertFalse(Files.exists(staging), "Isolation checks must not create any staged data");
    }

    @Test
    void invalidOtherLocalConfigurationFailsClosedInsteadOfSkippingIsolation() {
        when(sources.findByType(StorageTypeEnum.LOCAL)).thenReturn(List.of(source(9, true), source(10, false)));
        when(configs.findByStorageIdOrderById(10)).thenReturn(List.of());
        assertThrows(BizException.class, () -> service.requirePrivateStaging(work.resolve("private-staging")));
        when(configs.findByStorageIdOrderById(10)).thenReturn(List.of(config(Path.of("relative-cloud"))));
        assertThrows(BizException.class, () -> service.requirePrivateStaging(work.resolve("private-staging")));
    }

    @Test
    void traversalWindowsAlternateStreamsAndReservedNamesAreRejected() throws Exception {
        for (String path : List.of("../", "/../", "/tenant/./docs", "/C:/", "/path\\child", "/%2e%2e/", "/\n"))
            assertThrows(BizException.class, () -> LocalResumableUploadAccess.normalizedPath(path), path);
        for (String name : List.of("../x", "\\x", "x:stream", "CON", "aux.txt", "file.", "file ", "a?b", "%2f"))
            assertThrows(BizException.class, () -> LocalResumableUploadAccess.requireFilename(name), name);
        assertThrows(BizException.class, () -> LocalResumableUploadAccess.confined(cloud, "../outside"));
        assertEquals("/docs", LocalResumableUploadAccess.normalizedPath("//docs///"));
        LocalResumableUploadAccess.requireFilename("中文报告.txt");
    }

    @Test
    void rootIdentityDetectsReusedInodeBirthTimeAtFullPrecision() {
        var attributes = mock(BasicFileAttributes.class);
        when(attributes.isDirectory()).thenReturn(true);
        when(attributes.fileKey()).thenReturn("reused-inode");
        when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("2026-10-10T10:00:00.123456700Z")));
        String oldRoot = LocalResumableUploadAccess.directoryFingerprint(cloud, attributes);
        when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("2026-10-10T10:00:00.123456800Z")));
        assertNotEquals(oldRoot, LocalResumableUploadAccess.directoryFingerprint(cloud, attributes));
    }

    @Test
    void missingFileKeyUsesCanonicalPathAndFullPrecisionNativeBirthTime() {
        var attributes = mock(BasicFileAttributes.class);
        when(attributes.isDirectory()).thenReturn(true);
        when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("2026-10-10T10:00:00.123456700Z")));
        String original = LocalResumableUploadAccess.directoryFingerprint(cloud, attributes);
        assertEquals(original, LocalResumableUploadAccess.directoryFingerprint(cloud, attributes));
        assertNotEquals(original, LocalResumableUploadAccess.directoryFingerprint(cloud.resolveSibling("another-cloud"), attributes));
        when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("2026-10-10T10:00:00.123456800Z")));
        assertNotEquals(original, LocalResumableUploadAccess.directoryFingerprint(cloud, attributes));
    }

    @Test
    void unavailableOrDefaultBirthTimeFailsClosedEvenWithAFileKey() {
        var attributes = mock(BasicFileAttributes.class);
        when(attributes.isDirectory()).thenReturn(true);
        for (Object key : new Object[]{null, "reused-inode"}) {
            when(attributes.fileKey()).thenReturn(key);
            when(attributes.creationTime()).thenReturn(null);
            assertThrows(BizException.class, () -> LocalResumableUploadAccess.directoryFingerprint(cloud, attributes));
            when(attributes.creationTime()).thenReturn(FileTime.fromMillis(0));
            assertThrows(BizException.class, () -> LocalResumableUploadAccess.directoryFingerprint(cloud, attributes));
            // A zero Windows FILETIME converts to the Windows epoch, rather than the Unix epoch.
            when(attributes.creationTime()).thenReturn(FileTime.from(Instant.parse("1601-01-01T00:00:00Z")));
            assertThrows(BizException.class, () -> LocalResumableUploadAccess.directoryFingerprint(cloud, attributes));
        }
    }

    @Test
    void realTemporaryDirectoryUsesThePlatformIdentityWithoutSkippingWindows() throws Exception {
        Path directory = Files.createDirectory(work.resolve("real-platform-identity")).toRealPath();
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class);
        assertTrue(attributes.creationTime().compareTo(FileTime.fromMillis(0)) > 0);
        String identity = LocalResumableUploadAccess.directoryFingerprint(directory);
        assertEquals(LocalResumableUploadAccess.directoryFingerprint(directory, attributes), identity);
        Files.write(directory.resolve("changes-directory-modified-time"), new byte[]{1});
        assertEquals(identity, LocalResumableUploadAccess.directoryFingerprint(directory), "Uploading into the directory must not change its identity");
    }

    @Test
    void symlinkAliasesCannotExposeStagingOrRedirectOwnedTargets() throws Exception {
        Path alias = work.resolve("root-alias");
        try { Files.createSymbolicLink(alias, work); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) { assumeTrue(false, "OS symlink privileges unavailable"); }
        when(sources.findByType(StorageTypeEnum.LOCAL)).thenReturn(List.of(source(10, false)));
        when(configs.findByStorageIdOrderById(10)).thenReturn(List.of(config(alias)));
        assertThrows(BizException.class, () -> service.requirePrivateStaging(work.resolve("private-staging")));
        Path outside = Files.createDirectory(work.resolve("outside"));
        Files.createSymbolicLink(cloud.resolve("redirect"), outside);
        assertThrows(BizException.class, () -> LocalResumableUploadAccess.confined(cloud, "redirect/leak.bin"));
    }

    private static StorageSource source(int id, boolean enable) {
        var source = new StorageSource(); source.setId(id); source.setType(StorageTypeEnum.LOCAL); source.setEnable(enable); return source;
    }
    private static StorageSourceConfig config(Path path) {
        var config = new StorageSourceConfig(); config.setName("filePath"); config.setValue(path.toString()); return config;
    }
}

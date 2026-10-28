package io.studentcloud.content.http;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.HttpFailures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileBrowserControllerTest {
    @TempDir Path temporary;
    private final Actor admin = new Actor(1, "a1", "admin", "a@example.test", Role.ADMIN, null);
    private final Actor user = new Actor(3, "a3", "member", "m@example.test", Role.USER, 1L);
    private JdbcTemplate jdbc;
    private Path privateRoot;
    private MountService mounts;
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        Path managed = Files.createDirectory(temporary.resolve("managed"));
        Path mounted = Files.createDirectory(managed.resolve("one"));
        privateRoot = Files.createDirectories(mounted.resolve("members/u3"));
        Files.writeString(privateRoot.resolve("hello.txt"), "hello");
        Files.writeString(mounted.resolve("private-other.txt"), "invisible");
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:browser_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) { return id == 1 ? admin : user; }
            public boolean workspaceEnabled(long id) { return id == 1; }
        };
        mounts = new MountService(jdbc, new DataSourceTransactionManager(database), Clock.systemUTC(), managed, accounts);
        var mount = mounts.createLocal(admin, "个人云盘", "local-drive", mounted, 1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,mkdir,upload,copy,move,rename,delete',TRUE,1,1)", mount.id());
        mvc = MockMvcBuilders.standaloneSetup(new DriveController(mounts), new FileBrowserController(mounts))
                .setControllerAdvice(new HttpFailures())
                .addFilters(new AuthenticationFilter(token -> {
                    if (!"member".equals(token)) throw new ApplicationFault("42000", 401, "请先登录");
                    return user;
                }, new ObjectMapper())).build();
    }

    @Test void storageListUsesBrowserTypeWithoutChangingNativeDriveContract() throws Exception {
        mvc.perform(get("/api/storage/list").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("local-drive"))
                .andExpect(jsonPath("$.data[0].type.key").value("INDEPENDENT_LOCAL"));
        mvc.perform(get("/api/drives").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].engine").value("LOCAL"));
    }

    @Test void listingUsesParentPathsAndPrivateAuthenticatedContentUrls() throws Exception {
        mvc.perform(json("/api/storage/files", Map.of("storageKey", "local-drive", "path", "/")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].name").value("hello.txt"))
                .andExpect(jsonPath("$.data.files[0].path").value("/"))
                .andExpect(jsonPath("$.data.files[0].type").value("FILE"))
                .andExpect(jsonPath("$.data.files[0].size").value(5))
                .andExpect(jsonPath("$.data.files[0].time").value(org.hamcrest.Matchers.matchesPattern("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
                .andExpect(jsonPath("$.data.files[0].authenticatedContent").value(true))
                .andExpect(jsonPath("$.data.files[0].previewAvailable").value(true))
                .andExpect(jsonPath("$.data.files[0].previewKind").value("text"))
                .andExpect(jsonPath("$.data.files[0].previewUrl").value("/api/drives/local-drive/preview?path=%2Fhello.txt"))
                .andExpect(jsonPath("$.data.files[0].url").value("/api/drives/local-drive/content?path=%2Fhello.txt"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("members"))));
        mvc.perform(json("/api/storage/file/item", Map.of("storageKey", "local-drive", "path", "/hello.txt")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("hello.txt"));
        mvc.perform(json("/api/storage/file/item", Map.of("storageKey", "local-drive", "path", "/", "name", "hello.txt")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("hello.txt"));
        mvc.perform(json("/api/storage/files", Map.of("storageKey", "local-drive", "path", "/../")))
                .andExpect(status().isBadRequest());
        mvc.perform(json("/api/storage/file/item", Map.of("storageKey", "local-drive", "path", "/private-other.txt")))
                .andExpect(status().isNotFound());
    }

    @Test void previewMetadataAdvertisesOnlyLocalKindsWithinEachBudget() {
        for(var sample:Map.of("notes.md","markdown","source.js","text","lecture.mp4","video","recording.mp3","audio","lesson.docx","office","table.xlsx","office","deck.pptx","office","rows.csv","text","image.bmp","image").entrySet()){
            var file=new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/课程/"+sample.getKey()),sample.getKey(),false,16,java.time.Instant.EPOCH);
            var row=FileBrowserController.BrowserEntry.of("local-drive",file);
            assertTrue(row.previewAvailable(),sample.getKey());assertEquals(sample.getValue(),row.previewKind());
            assertTrue(row.previewUrl().startsWith("/api/drives/local-drive/preview?path="));
            assertFalse(row.previewUrl().contains("token"));
        }
        for(String name:java.util.List.of("old.doc","old.xls","old.ppt","archive.zip","binary.exe")){
            var row=FileBrowserController.BrowserEntry.of("local-drive",new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/"+name),name,false,16,java.time.Instant.EPOCH));
            assertFalse(row.previewAvailable());assertNull(row.previewKind());assertNull(row.previewUrl());
        }
        for(String name:java.util.List.of("mesh.stl","mesh.obj")){
            var row=FileBrowserController.BrowserEntry.of("local-drive",new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/"+name),name,false,128,java.time.Instant.EPOCH));
            assertTrue(row.previewAvailable());assertEquals("model",row.previewKind());
        }
        var oversizedModel=FileBrowserController.BrowserEntry.of("local-drive",new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/mesh.obj"),"mesh.obj",false,10L*1048576+1,java.time.Instant.EPOCH));
        assertFalse(oversizedModel.previewAvailable());
        for(var sample:Map.of("large.docx",10L*1048576+1,"large.mp4",50L*1048576+1,"large.java",1048577L,"large.pdf",20L*1048576+1).entrySet()){
            var row=FileBrowserController.BrowserEntry.of("local-drive",new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/"+sample.getKey()),sample.getKey(),false,sample.getValue(),java.time.Instant.EPOCH));
            assertFalse(row.previewAvailable());assertNull(row.previewUrl());
        }
    }

    @Test void configurationReflectsFreshPermissionsAndNeverExposesGrantRoot() throws Exception {
        mvc.perform(json("/api/site/config/storage", Map.of("storageKey", "local-drive", "path", "/")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rootPath").value("/"))
                .andExpect(jsonPath("$.data.permission.newFolder").value(true))
                .andExpect(jsonPath("$.data.permission.copy").value(true))
                .andExpect(jsonPath("$.data.permission.download").value(true))
                .andExpect(jsonPath("$.data.permission.preview").value(true))
                .andExpect(jsonPath("$.data.permission.share").value(false))
                .andExpect(jsonPath("$.data.permission.batchDownload").value(false))
                .andExpect(jsonPath("$.data.metadata.uploadType").value("PROXY"))
                .andExpect(jsonPath("$.data.metadata.supportCopyFolder").value(true))
                .andExpect(jsonPath("$.data.metadata.needCreateFolderBeforeUpload").value(true));
        jdbc.update("UPDATE sc_drive_grant SET permissions='list,copy',revision=revision+1");
        mvc.perform(json("/api/site/config/storage", Map.of("key", "local-drive", "path", "/")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permission.copy").value(false))
                .andExpect(jsonPath("$.data.permission.upload").value(false));
        mvc.perform(get("/api/site/config/userRootPath/local-drive").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("/"));
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE");
        mvc.perform(json("/api/site/config/storage", Map.of("storageKey", "local-drive", "path", "/")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/site/config/userRootPath/local-drive").header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden());
    }

    @Test void mkdirRenameCopyMoveAndDeleteUseCoreAndReportPartialResults() throws Exception {
        mvc.perform(json("/api/file/operator/mkdir", Map.of("storageKey", "local-drive", "path", "/", "name", "folder")))
                .andExpect(status().isOk());
        assertTrue(Files.isDirectory(privateRoot.resolve("folder")));
        mvc.perform(json("/api/file/operator/rename/file", Map.of("storageKey", "local-drive", "path", "/", "name", "hello.txt", "newName", "renamed.txt")))
                .andExpect(status().isOk());
        mvc.perform(json("/api/file/operator/copy/file", Map.of("storageKey", "local-drive", "path", "/", "nameList", java.util.List.of("renamed.txt", "missing.txt"), "targetPath", "/folder", "targetNameList", java.util.List.of("copy.txt", "missing.txt"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].success").value(true))
                .andExpect(jsonPath("$.data[1].success").value(false));
        assertEquals("hello", Files.readString(privateRoot.resolve("folder/copy.txt")));
        mvc.perform(json("/api/file/operator/move/file", Map.of("storageKey", "local-drive", "path", "/folder", "nameList", java.util.List.of("copy.txt"), "targetPath", "/", "targetNameList", java.util.List.of("moved.txt"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].success").value(true));
        mvc.perform(json("/api/file/operator/delete/batch", Map.of("storageKey", "local-drive", "deleteItems", java.util.List.of(Map.of("path", "/", "name", "moved.txt"), Map.of("path", "/", "name", "missing.txt")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].success").value(true))
                .andExpect(jsonPath("$.data[1].success").value(false))
                .andExpect(jsonPath("$.data[1].message").isNotEmpty());
        assertFalse(Files.exists(privateRoot.resolve("moved.txt")));
        assertTrue(Files.exists(privateRoot.resolve("renamed.txt")));
        jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");
        mvc.perform(json("/api/file/operator/rename/file", Map.of("storageKey", "local-drive", "path", "/", "name", "renamed.txt", "newName", "denied.txt")))
                .andExpect(status().isForbidden());
        assertTrue(Files.exists(privateRoot.resolve("renamed.txt")));
    }

    @Test void batchLimitsAndMismatchedTargetNamesRejectBeforeMutating() throws Exception {
        mvc.perform(json("/api/file/operator/copy/file", Map.of("storageKey", "local-drive", "path", "/", "nameList", java.util.List.of("hello.txt"), "targetPath", "/", "targetNameList", java.util.List.of())))
                .andExpect(status().isBadRequest());
        mvc.perform(json("/api/file/operator/delete/batch", Map.of("storageKey", "local-drive", "deleteItems", java.util.Collections.nCopies(21, Map.of("path", "/", "name", "hello.txt")))))
                .andExpect(status().isBadRequest());
        assertTrue(Files.exists(privateRoot.resolve("hello.txt")));
    }

    @Test void invalidTransferElementsRejectWholeRequestBeforeFirstCopy() throws Exception {
        mvc.perform(json("/api/file/operator/copy/file", Map.of("storageKey", "local-drive", "path", "/",
                "nameList", java.util.List.of("hello.txt", "hello.txt"), "targetPath", "/",
                "targetNameList", java.util.List.of("copy.txt", "invalid/name"))))
                .andExpect(status().isBadRequest());
        assertFalse(Files.exists(privateRoot.resolve("copy.txt")));
        mvc.perform(json("/api/file/operator/copy/file", Map.of("storageKey", "local-drive", "path", "/",
                "nameList", java.util.Arrays.asList("hello.txt", null), "targetPath", "/",
                "targetNameList", java.util.List.of("copy.txt", "second.txt"))))
                .andExpect(status().isBadRequest());
        mvc.perform(json("/api/file/operator/copy/file", Map.of("storageKey", "local-drive", "path", "/",
                "nameList", java.util.List.of("hello.txt"), "targetPath", "/",
                "targetNameList", java.util.List.of("x".repeat(256)))))
                .andExpect(status().isBadRequest());
        assertFalse(Files.exists(privateRoot.resolve("copy.txt")));
    }

    @Test void invalidLaterDeletePathRejectsBeforeDeletingTheFirstFile() throws Exception {
        mvc.perform(json("/api/file/operator/delete/batch", Map.of("storageKey", "local-drive", "deleteItems",
                java.util.List.of(Map.of("path", "/", "name", "hello.txt"), Map.of("path", "/", "name", "invalid/name")))))
                .andExpect(status().isBadRequest());
        assertTrue(Files.exists(privateRoot.resolve("hello.txt")));
    }

    @Test void uploadPreparationValidatesParentSizeAndGrantAndPutChecksFreshGrant() throws Exception {
        mvc.perform(json("/api/file/operator/upload/file", Map.of("storageKey", "local-drive", "path", "/", "name", "new file.txt", "size", 5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("/api/drives/local-drive/files?path=%2Fnew%20file.txt"));
        mvc.perform(json("/api/file/operator/upload/file", Map.of("storageKey", "local-drive", "path", "/missing", "name", "new.txt", "size", 5)))
                .andExpect(status().isNotFound());
        mvc.perform(json("/api/file/operator/upload/file", Map.of("storageKey", "local-drive", "path", "/hello.txt", "name", "new.txt", "size", 5)))
                .andExpect(status().isBadRequest());
        mvc.perform(json("/api/file/operator/upload/file", Map.of("storageKey", "local-drive", "path", "/", "name", "too-big.txt", "size", 52428801)))
                .andExpect(status().isBadRequest());
        mvc.perform(json("/api/file/operator/upload/file", Map.of("storageKey", "local-drive", "path", "/", "name", "hello.txt", "size", 5)))
                .andExpect(status().isConflict());
        var file = new MockMultipartFile("file", "any.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/drives/local-drive/files").file(file).param("path", "/new file.txt").header("Authorization", "Bearer member"))
                .andExpect(status().isOk());
        assertEquals("hello", Files.readString(privateRoot.resolve("new file.txt")));
        jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/drives/local-drive/files").file(file).param("path", "/denied.txt").header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden());
        assertFalse(Files.exists(privateRoot.resolve("denied.txt")));
    }

    @Test void allBrowserEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/storage/list")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/storage/files").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/file/operator/upload/file").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder json(String url, Object body) {
        return post(url).header("Authorization", "Bearer member").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(body));
    }
}

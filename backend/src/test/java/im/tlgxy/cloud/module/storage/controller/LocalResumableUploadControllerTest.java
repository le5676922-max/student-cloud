package im.tlgxy.cloud.module.storage.controller;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.service.LocalResumableUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalResumableUploadControllerTest {
    @Test
    void unicodeDirectoryPasswordIsDecodedAtHttpBoundaryAndActorCannotBeInjected() throws Exception {
        var uploads = mock(LocalResumableUploadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LocalResumableUploadController(uploads)).build();
        String password = "中文密码+ 100%";
        String header = URLEncoder.encode(password, StandardCharsets.UTF_8).replace("+", "%20");
        var view = new LocalResumableUploadService.SessionView("c41f66a6-1b11-4cb7-b0f1-234fabbece07", "OPEN", 4_194_304, 3, List.of(0), 1_800_000_000_000L, "continue");
        when(uploads.capabilities(eq(7), any(), eq(password))).thenReturn(new LocalResumableUploadService.Capabilities(true, 4_194_304, 2_147_483_648L, 86_400, "local"));
        when(uploads.initialize(eq(7), any(), eq(password))).thenReturn(view);
        when(uploads.status(7, view.id(), password)).thenReturn(view);
        when(uploads.receiveChunk(eq(7), eq(view.id()), eq(0), eq("a".repeat(64)), any(), eq(password))).thenReturn(view);
        when(uploads.complete(7, view.id(), password)).thenReturn(view);
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(7);
            String input = "{\"storageKey\":\"local\",\"path\":\"/\",\"name\":\"报告.txt\",\"size\":3,\"actorId\":999}";
            mvc.perform(post("/api/uploads/resumable/capabilities").header("X-Upload-Directory-Password", header).contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0")).andExpect(jsonPath("$.data.enabled").value(true));
            mvc.perform(post("/api/uploads/resumable/init").header("X-Upload-Directory-Password", header).contentType(MediaType.APPLICATION_JSON)
                    .content(input.substring(0, input.length() - 1) + ",\"manifestHash\":\"" + "b".repeat(64) + "\",\"chunkHashes\":[\"" + "a".repeat(64) + "\"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(view.id()));
            mvc.perform(get("/api/uploads/resumable/" + view.id()).header("X-Upload-Directory-Password", header))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").value(view.expiresAt()));
            mvc.perform(put("/api/uploads/resumable/" + view.id() + "/chunks/0").header("X-Upload-Directory-Password", header)
                    .header("X-Upload-Chunk-SHA256", "a".repeat(64)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[]{1, 2, 3}))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/uploads/resumable/" + view.id() + "/complete").header("X-Upload-Directory-Password", header)).andExpect(status().isOk());
            mvc.perform(delete("/api/uploads/resumable/" + view.id()).header("X-Upload-Directory-Password", header))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("仅续传暂存已清理，云盘文件未删除"));
        }
        verify(uploads).delete(7, view.id(), password);
        verify(uploads, never()).capabilities(eq(999), any(), any());
    }

    @Test
    void malformedAndUnboundedPasswordHeaderIsRejected() {
        assertNull(LocalResumableUploadController.decodePassword(null));
        assertEquals("literal%+ 中文", LocalResumableUploadController.decodePassword("literal%25%2B%20%E4%B8%AD%E6%96%87"));
        assertThrows(BizException.class, () -> LocalResumableUploadController.decodePassword("%ZZ"));
        assertThrows(BizException.class, () -> LocalResumableUploadController.decodePassword("a".repeat(9001)));
        assertThrows(BizException.class, () -> LocalResumableUploadController.decodePassword("%61".repeat(1001)));
        assertThrows(BizException.class, () -> LocalResumableUploadController.decodePassword("密码"));
    }
}

package im.tlgxy.cloud.core.util;

import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareDownloadTokenUtilsTest {
    private final AtomicReference<String> key = new AtomicReference<>("00112233445566778899aabbccddeeff");
    private final SystemConfigService config = mock(SystemConfigService.class);
    private MockedStatic<SpringUtil> spring;
    private Object originalConfig;

    @BeforeEach void setup() {
        when(config.getAesHexKeyOrGenerate()).thenAnswer(invocation -> key.get());
        spring = mockStatic(SpringUtil.class);
        spring.when(() -> SpringUtil.getBean(SystemConfigService.class)).thenReturn(config);
        originalConfig = ReflectionTestUtils.getField(ProxyDownloadUrlUtils.class, "systemConfigService");
        ReflectionTestUtils.setField(ProxyDownloadUrlUtils.class, "systemConfigService", config);
    }

    @AfterEach void cleanup() {
        ReflectionTestUtils.setField(ProxyDownloadUrlUtils.class, "systemConfigService", originalConfig);
        spring.close();
    }

    @Test void bindsShareStoragePathAndAllowsUnicodeSpecialFilenames() {
        String path = "/课程/成绩 & +.txt";
        String ticket = ShareDownloadTokenUtils.generate(7, path, "safeKey", 60);
        assertEquals("safeKey", ShareDownloadTokenUtils.validate(7, path, ticket).shareKey());
        assertTrue(ProxyDownloadUrlUtils.validSignatureExpired(7, path, ticket));
        assertNull(ShareDownloadTokenUtils.validate(8, path, ticket));
        assertNull(ShareDownloadTokenUtils.validate(7, "/课程/other.txt", ticket));
    }

    @Test void tamperingOrMalformedTokenCannotFallBackToLegacySignature() {
        String ticket = ShareDownloadTokenUtils.generate(7, "/item.txt", "safeKey", 60);
        String changed = ticket.substring(0, ticket.length() - 1) + (ticket.endsWith("0") ? "1" : "0");
        assertFalse(ProxyDownloadUrlUtils.validSignatureExpired(7, "/item.txt", changed));
        for (String invalid : new String[]{"SHARE1.", "SHARE1.a.b", "SHARE1.a.b.extra", "SHARE1." + "a".repeat(32768)}) {
            assertNull(ShareDownloadTokenUtils.validate(7, "/item.txt", invalid));
        }
    }

    @Test void keyRotationRevokesBothShareAndPreviouslyIssuedOrdinaryTickets() {
        String share = ShareDownloadTokenUtils.generate(7, "/item.txt", "safeKey", 60);
        String ordinary = ProxyDownloadUrlUtils.generatorSignature(7, "/item.txt", 60);
        String permanent = ProxyDownloadUrlUtils.generatorPermanentSignature(7, "/item.txt");
        assertTrue(ProxyDownloadUrlUtils.validSignatureExpired(7, "/item.txt", ordinary));
        assertTrue(ProxyDownloadUrlUtils.validSignatureExpired(7, "/item.txt", permanent));
        key.set("ffeeddccbbaa99887766554433221100");
        assertNull(ShareDownloadTokenUtils.validate(7, "/item.txt", share));
        assertFalse(ProxyDownloadUrlUtils.validSignatureExpired(7, "/item.txt", ordinary));
        assertFalse(ProxyDownloadUrlUtils.validSignatureExpired(7, "/item.txt", permanent));
        assertNotNull(ShareDownloadTokenUtils.validate(7, "/item.txt",
                ShareDownloadTokenUtils.generate(7, "/item.txt", "safeKey", 60)));
    }
}

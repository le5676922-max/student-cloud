package im.tlgxy.cloud.module.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.config.controller.SiteController;
import im.tlgxy.cloud.module.config.mapper.SystemConfigMapper;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnonymousConfigRetirementTest {
    @Test
    void legacyAnonymousSettingsCannotBeLoadedOrReenabled() throws Exception {
        var mapper = mock(SystemConfigMapper.class);
        when(mapper.findAll()).thenReturn(List.of(config("webdavAllowAnonymous", "true"),
                config("allowPathLinkAnonAccess", "true"), config("guestIndexHtml", "retired homepage")));
        var service = new SystemConfigService();
        ReflectionTestUtils.setField(service, "systemConfigMapper", mapper);
        var result = service.getSystemConfig();
        assertFalse(result.getWebdavAllowAnonymous());
        assertFalse(result.getAllowPathLinkAnonAccess());
        result.setWebdavAllowAnonymous(true);
        result.setAllowPathLinkAnonAccess(true);
        assertFalse(result.getWebdavAllowAnonymous());
        assertFalse(result.getAllowPathLinkAnonAccess());
        var json = new ObjectMapper().valueToTree(result);
        assertFalse(json.has("webdavAllowAnonymous"));
        assertFalse(json.has("allowPathLinkAnonAccess"));
        assertFalse(json.has("guestIndexHtml"));
    }

    @Test
    void publicBootstrapHasNoGuestStateOrUserLookup() {
        var configs = mock(SystemConfigService.class);
        var config = new SystemConfigDTO();
        config.setSiteName("Test cloud");
        when(configs.getSystemConfig()).thenReturn(config);
        var controller = new SiteController();
        ReflectionTestUtils.setField(controller, "systemConfigService", configs);
        ReflectionTestUtils.setField(controller, "tlgxyCloudProperties", new TlgxyCloudProperties());
        try (var identities = mockStatic(TlgxyCloudAuthUtil.class)) {
            var json = new ObjectMapper().valueToTree(controller.globalConfig().getData());
            assertEquals("Test cloud", json.path("siteName").asText());
            assertFalse(json.has("guest"));
            identities.verifyNoInteractions();
        }
    }

    private SystemConfig config(String key, String value) {
        var config = new SystemConfig();
        config.setName(key);
        config.setValue(value);
        return config;
    }
}

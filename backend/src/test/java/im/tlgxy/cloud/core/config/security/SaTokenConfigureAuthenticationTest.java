package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.OnlyOfficeDownloadTokenUtils;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.module.onlyoffice.controller.OnlyOfficeController;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeCallback;
import im.tlgxy.cloud.module.storage.controller.proxy.ProxyDownloadController;
import im.tlgxy.cloud.module.user.controller.UserController;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.config.controller.SiteController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaTokenConfigureAuthenticationTest {
    private final UserService users = mock(UserService.class);
    private final SaTokenConfigure config = new SaTokenConfigure();

    SaTokenConfigureAuthenticationTest() {
        ReflectionTestUtils.setField(config, "userService", users);
    }

    @Test
    void downloadSignatureAndLoginPrefixDoNotExemptBusinessRequests() throws Exception {
        HandlerMethod business = new HandlerMethod(new ProxyDownloadController(), "downAttachment", String.class, String.class, String.class);
        for (String path : new String[]{"/pd/files/private.txt", "/api/share/files", "/user/login/extra", "/user/resetAdminPassword"}) {
            try (var requests = mockStatic(RequestHolder.class); var tokens = mockStatic(StpUtil.class)) {
                requests.when(RequestHolder::getRequest).thenReturn(request("GET", path));
                tokens.when(StpUtil::checkLogin).thenThrow(new BizException("login required"));
                assertThrows(BizException.class, () -> authenticate(business), path);
            }
        }
        verifyNoInteractions(users);
    }

    @Test
    void registeredCustomLoginAndExactBootstrapRemainPublic() throws Exception {
        HandlerMethod login = new HandlerMethod(mock(UserController.class), "doLogin", UserLoginRequest.class);
        try (var requests = mockStatic(RequestHolder.class); var tokens = mockStatic(StpUtil.class)) {
            requests.when(RequestHolder::getRequest).thenReturn(request("POST", "/user/login/configured-entry"));
            assertDoesNotThrow(() -> authenticate(login));
            tokens.verify(StpUtil::checkLogin, never());
        }
        HandlerMethod bootstrap = new HandlerMethod(new SiteController(), "globalConfig");
        try (var requests = mockStatic(RequestHolder.class); var tokens = mockStatic(StpUtil.class)) {
            requests.when(RequestHolder::getRequest).thenReturn(request("GET", "/api/site/config/global"));
            assertDoesNotThrow(() -> authenticate(bootstrap));
            requests.when(RequestHolder::getRequest).thenReturn(request("POST", "/api/site/config/global"));
            tokens.when(StpUtil::checkLogin).thenThrow(new BizException("login required"));
            assertThrows(BizException.class, () -> authenticate(bootstrap));
        }
    }

    @Test
    void documentCredentialRequiresFreshEnabledViewerAndCannotExemptAnotherEndpoint() throws Exception {
        String document = OnlyOfficeDownloadTokenUtils.documentUrl("http://localhost/pd/files/test.docx?signature=synthetic", 6);
        URI documentUri = URI.create(document);
        MockHttpServletRequest download = request("GET", documentUri.getRawPath());
        download.setQueryString(documentUri.getRawQuery());
        download.addParameter(OnlyOfficeDownloadTokenUtils.PARAMETER,
                documentUri.getRawQuery().substring(documentUri.getRawQuery().lastIndexOf('=') + 1));
        HandlerMethod proxy = new HandlerMethod(new ProxyDownloadController(), "downAttachment", String.class, String.class, String.class);
        try (var requests = mockStatic(RequestHolder.class); var tokens = mockStatic(StpUtil.class)) {
            requests.when(RequestHolder::getRequest).thenReturn(download);
            assertDoesNotThrow(() -> authenticate(proxy));
            verify(users).requireLoginUser(6);
            when(users.requireLoginUser(6)).thenThrow(new BizException("disabled viewer"));
            assertThrows(BizException.class, () -> authenticate(proxy));

            HandlerMethod other = new HandlerMethod(mock(UserController.class), "checkLogin");
            download.setServletPath("/api/private");
            tokens.when(StpUtil::checkLogin).thenThrow(new BizException("login required"));
            assertThrows(BizException.class, () -> authenticate(other));
        }
    }

    @Test
    void onlyExactPostCallbackUsesProtocolAuthentication() throws Exception {
        HandlerMethod callback = new HandlerMethod(new OnlyOfficeController(), "callBack", OnlyOfficeCallback.class);
        try (var requests = mockStatic(RequestHolder.class); var tokens = mockStatic(StpUtil.class)) {
            requests.when(RequestHolder::getRequest).thenReturn(request("POST", "/onlyOffice/callback"));
            assertDoesNotThrow(() -> authenticate(callback));
            requests.when(RequestHolder::getRequest).thenReturn(request("GET", "/onlyOffice/callback"));
            tokens.when(StpUtil::checkLogin).thenThrow(new BizException("login required"));
            assertThrows(BizException.class, () -> authenticate(callback));
        }
    }

    @Test
    void existingSessionsAreCheckedAgainstCurrentAccountState() throws Exception {
        HandlerMethod business = new HandlerMethod(new ProxyDownloadController(), "downAttachment", String.class, String.class, String.class);
        try (var requests = mockStatic(RequestHolder.class); var tokens = mockStatic(StpUtil.class)) {
            requests.when(RequestHolder::getRequest).thenReturn(request("GET", "/pd/files/test.docx"));
            tokens.when(StpUtil::isLogin).thenReturn(true);
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(6);
            when(users.requireLoginUser(6)).thenThrow(new BizException("disabled"));
            assertThrows(BizException.class, () -> authenticate(business));
        }
    }

    private void authenticate(HandlerMethod handler) {
        ReflectionTestUtils.invokeMethod(config, "requireAuthenticatedRequest", handler);
    }

    private static MockHttpServletRequest request(String verb, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(verb, path);
        request.setServletPath(path);
        return request;
    }
}

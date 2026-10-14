package im.tlgxy.cloud.module.log.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.link.model.request.QueryLoginLogRequest;
import im.tlgxy.cloud.module.log.model.entity.LoginLog;
import im.tlgxy.cloud.module.log.service.LoginLogService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginLogControllerTest {
    @BeforeAll
    static void initializeLogMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "login-log-controller-test"), LoginLog.class);
    }

    @Test
    void authorizationPrecedesValidationAndAnyMapperAccess() {
        var logs = mock(LoginLogService.class);
        var security = mock(TenantSecurityService.class);
        BizException denied = new BizException("只有系统管理员可以执行此操作");
        doThrow(denied).when(security).requireSystemAdmin();
        var query = new QueryLoginLogRequest();
        query.setLimit(99999999);
        assertSame(denied, assertThrows(BizException.class, () -> controller(logs, security).list(query)));
        verifyNoInteractions(logs);
    }

    @Test
    void malformedQueryNeverReachesTheMapperForAnAdministrator() {
        var logs = mock(LoginLogService.class);
        var security = mock(TenantSecurityService.class);
        var query = new QueryLoginLogRequest();
        query.setOrderBy("id, (SELECT 1)");
        assertThrows(BizException.class, () -> controller(logs, security).list(query));
        verify(security).requireSystemAdmin();
        verifyNoInteractions(logs);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legacyPasswordFilterIsIgnoredAndResponsesOmitTheHistoricalField() {
        var logs = mock(LoginLogService.class);
        var security = mock(TenantSecurityService.class);
        var log = new LoginLog();
        log.setId(8);
        log.setUsername("example-user");
        log.setPassword("historical-value-never-returned");
        log.setResult("登录成功");
        Page<LoginLog> resultPage = new Page<>();
        resultPage.setTotal(1);
        resultPage.setRecords(List.of(log));
        when(logs.selectPage(any(), any())).thenReturn(resultPage);
        var query = new QueryLoginLogRequest();
        query.setPassword("legacy-filter-never-queried");
        query.setSearchDate(List.of());
        var response = controller(logs, security).list(query);

        var callOrder = inOrder(security, logs);
        callOrder.verify(security).requireSystemAdmin();
        ArgumentCaptor<Page<LoginLog>> page = (ArgumentCaptor) ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<LambdaQueryWrapper> where = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        callOrder.verify(logs).selectPage(page.capture(), where.capture());
        assertEquals(1, page.getValue().getCurrent());
        assertEquals(10, page.getValue().getSize());
        assertEquals("create_time", page.getValue().orders().getFirst().getColumn());
        assertFalse(where.getValue().getSqlSelect().contains("password"));
        assertFalse(where.getValue().getSqlSegment().contains("password"));
        assertFalse(where.getValue().getParamNameValuePairs().containsValue(query.getPassword()));
        assertEquals(1L, response.getDataCount());
        var json = new ObjectMapper().valueToTree(response.getData().getFirst());
        assertEquals("example-user", json.path("username").asText());
        assertEquals("登录成功", json.path("result").asText());
        assertFalse(json.has("password"));
    }

    private LoginLogController controller(LoginLogService logs, TenantSecurityService security) {
        var controller = new LoginLogController();
        ReflectionTestUtils.setField(controller, "loginLogService", logs);
        ReflectionTestUtils.setField(controller, "tenantSecurityService", security);
        return controller;
    }
}

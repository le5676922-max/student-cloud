package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLoginIdentifierTest {
    @BeforeAll
    static void initializeUserMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "identity-test"), User.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void emailLookupTrimsIdentifierAndChecksEveryAliasTogether() {
        UserMapper mapper = mock(UserMapper.class);
        User user = new User();
        user.setId(9);
        when(mapper.selectList(any())).thenReturn(List.of(user));
        UserService service = service(mapper);
        assertSame(user, service.getByLoginIdentifier("  Student@Example.COM  "));
        ArgumentCaptor<LambdaQueryWrapper> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("username"));
        assertTrue(sql.contains("account_id"));
        assertTrue(sql.contains("email"));
        assertTrue(sql.contains(" OR "));
        assertTrue(sql.contains("LIMIT 2"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("Student@Example.COM"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("student@example.com"));
    }

    @Test
    void accountIdAndLegacyUsernameResolveWithTheSameLookup() {
        UserMapper mapper = mock(UserMapper.class);
        User user = new User();
        user.setId(9);
        when(mapper.selectList(any())).thenReturn(List.of(user));
        UserService service = service(mapper);
        assertSame(user, service.getByLoginIdentifier("SC0123456789ABCDEF"));
        assertSame(user, service.getByLoginIdentifier(" legacy-student "));
    }

    @Test
    void identifierCollisionIsRejectedWithTheGenericCredentialError() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(new User(), new User()));
        BizException error = assertThrows(BizException.class,
                () -> service(mapper).getByLoginIdentifier("colliding@example.com"));
        assertEquals(ErrorCode.BIZ_LOGIN_ERROR.getCode(), error.getCode());
        assertEquals(ErrorCode.BIZ_LOGIN_ERROR.getMessage(), error.getMessage());
    }

    @Test
    void missingIdentifiersNeverMatchAnyAccount() {
        UserMapper mapper = mock(UserMapper.class);
        UserService service = service(mapper);
        assertNull(service.getByLoginIdentifier(null));
        assertNull(service.getByLoginIdentifier("  "));
        verifyNoInteractions(mapper);
        when(mapper.selectList(any())).thenReturn(List.of());
        assertNull(service.getByLoginIdentifier("missing@example.com"));
    }

    @Test
    void emailIsNormalizedWhenAssigned() {
        User user = new User();
        user.setEmail("  STUDENT@Example.COM ");
        assertEquals("student@example.com", user.getEmail());
        user.setEmail("  ");
        assertNull(user.getEmail());
        user.setEmail(null);
        assertNull(user.getEmail());
    }

    private UserService service(UserMapper mapper) {
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        return service;
    }
}

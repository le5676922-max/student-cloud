package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdministratorStorageListingTest {
    @Test void freshAdministratorBypassesAnOldAccountScopedStorageCache() {
        var users = mock(UserService.class);
        var mapper = mock(StorageSourceMapper.class);
        var cache = new TlgxyCloudCacheManager();
        cache.findAllEnableOrderByOrderNum(10, ignored -> List.of());
        var source = new StorageSource(); source.setKey("workspace-a");
        when(mapper.findAdministratorEnableList()).thenReturn(List.of(source));
        when(users.requireLoginUser(10)).thenReturn(user(UserRoleEnum.ADMIN));
        var service = service(users, mapper, cache);
        assertEquals(List.of(source), service.findAllEnableOrderByOrderNum(10));
        verify(mapper).findAdministratorEnableList();
        verify(mapper, never()).findUserEnableList(any());
    }

    @Test void ordinaryAccountsStillUseTheirBoundedCachedAssignedStorageList() {
        var users = mock(UserService.class);
        var mapper = mock(StorageSourceMapper.class);
        when(users.requireLoginUser(10)).thenReturn(user(UserRoleEnum.USER));
        when(mapper.findUserEnableList(10)).thenReturn(List.of());
        var service = service(users, mapper, new TlgxyCloudCacheManager());
        assertTrue(service.findAllEnableOrderByOrderNum(10).isEmpty());
        assertTrue(service.findAllEnableOrderByOrderNum(10).isEmpty());
        verify(mapper, times(1)).findUserEnableList(10);
        verify(mapper, never()).findAdministratorEnableList();
        verify(users, times(2)).requireLoginUser(10);
    }

    @Test void accountValidationStillRunsBeforeAnyCachedListCanBeReturned() {
        var users = mock(UserService.class);
        var mapper = mock(StorageSourceMapper.class);
        var cache = new TlgxyCloudCacheManager();
        cache.findAllEnableOrderByOrderNum(10, ignored -> List.of(new StorageSource()));
        when(users.requireLoginUser(10)).thenThrow(new BizException("账号已停用"));
        var service = service(users, mapper, cache);
        assertThrows(BizException.class, () -> service.findAllEnableOrderByOrderNum(10));
        assertTrue(service.findAllEnableOrderByOrderNum(null).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test void actualAdministratorListSqlIncludesOnlyEnabledSourcesWithoutGrantRowsOrDuplicates() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        String sql = null;
        try (var xml = getClass().getResourceAsStream("/mapper/StorageSourceMapper.xml")) {
            var statements = factory.newDocumentBuilder().parse(xml).getElementsByTagName("select");
            for (int index = 0; index < statements.getLength(); index++) {
                var statement = (Element) statements.item(index);
                if ("findAdministratorEnableList".equals(statement.getAttribute("id"))) sql = statement.getTextContent();
            }
        }
        assertNotNull(sql);
        try (var database = DriverManager.getConnection("jdbc:sqlite::memory:"); var setup = database.createStatement()) {
            setup.execute("CREATE TABLE storage_source (id INTEGER, name TEXT, key TEXT, type TEXT, search_enable BOOLEAN, default_switch_to_img_mode BOOLEAN, enable BOOLEAN, order_num INTEGER,tenant_id INTEGER,owner_user_id INTEGER)");
            setup.execute("INSERT INTO storage_source(id,name,key,type,search_enable,default_switch_to_img_mode,enable,order_num) VALUES (1, 'A', 'first', 'LOCAL', 0, 0, 1, 2), (2, 'B', 'second', 'LOCAL', 0, 0, 1, 1), (3, 'Off', 'off', 'LOCAL', 0, 0, 0, 0)");
            var keys = new ArrayList<String>();
            try (var rows = setup.executeQuery(sql)) { while (rows.next()) keys.add(rows.getString("key")); }
            assertEquals(List.of("second", "first"), keys);
        }
    }

    @Test void shareBaseRemainsPriorToTheAdministratorsUnrestrictedRoot() {
        var permissions = mock(UserStorageSourceService.class);
        var storage = new LocalServiceImpl();
        ReflectionTestUtils.setField(storage, "userStorageSourceService", permissions);
        ShareAccessContext.setShareAccess("synthetic-share", "/only-selected", 10);
        try {
            assertEquals("/only-selected", storage.getCurrentUserBasePath());
            verifyNoInteractions(permissions);
        } finally { ShareAccessContext.clear(); }
    }

    private static StorageSourceService service(UserService users, StorageSourceMapper mapper, TlgxyCloudCacheManager cache) {
        var service = new StorageSourceService();
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "storageSourceMapper", mapper);
        ReflectionTestUtils.setField(service, "studentCloudCacheManager", cache);
        return service;
    }
    private static User user(UserRoleEnum role) {
        var user = new User(); user.setId(10); user.setEnable(true); user.setRole(role); return user;
    }
}

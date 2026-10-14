package im.tlgxy.cloud.core.config.mybatis;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.module.user.model.entity.User;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserAccountIdFillTest {
    @BeforeAll
    static void initializeUserMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "identity-fill-test"), User.class);
    }

    @Test
    void insertGeneratesRandomAccountIdsWithoutChangingExistingValues() {
        MyMetaObjectHandler handler = new MyMetaObjectHandler();
        User first = new User();
        User second = new User();
        handler.insertFill(SystemMetaObject.forObject(first));
        handler.insertFill(SystemMetaObject.forObject(second));
        assertTrue(first.getAccountId().matches("SC[0-9A-F]{16}"));
        assertTrue(second.getAccountId().matches("SC[0-9A-F]{16}"));
        assertNotEquals(first.getAccountId(), second.getAccountId());
        assertNotNull(first.getCreateTime());
        String original = first.getAccountId();
        handler.insertFill(SystemMetaObject.forObject(first));
        assertEquals(original, first.getAccountId());
    }

    @Test
    void updateDoesNotGenerateOrModifyAccountIdAndMappingPreventsUpdates() {
        MyMetaObjectHandler handler = new MyMetaObjectHandler();
        User empty = new User();
        handler.updateFill(SystemMetaObject.forObject(empty));
        assertNull(empty.getAccountId());
        User existing = new User();
        existing.setAccountId("SC0123456789ABCDEF");
        handler.updateFill(SystemMetaObject.forObject(existing));
        assertEquals("SC0123456789ABCDEF", existing.getAccountId());
        var accountField = TableInfoHelper.getTableInfo(User.class).getFieldList().stream()
                .filter(field -> "accountId".equals(field.getProperty())).findFirst().orElseThrow();
        assertEquals(FieldStrategy.NEVER, accountField.getUpdateStrategy());
    }
}

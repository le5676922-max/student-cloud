package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.param.LocalParam;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real storage bytes and cross-instance SQLite locks; no production files or network. */
class MemberStorageQuotaTest {
    @TempDir Path root;
    JdbcTemplate jdbc; DriverManagerDataSource data; UserService users;
    UserStorageSourceMapper grants; LocalServiceImpl driver; MemberStorageQuotaService quota;
    Path personal;
    @BeforeEach void fixture() throws Exception {
        data = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("quota.db")); data.setDriverClassName("org.sqlite.JDBC");
        Properties properties = new Properties(); properties.setProperty("busy_timeout", "10000"); data.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE user_storage_source(user_id INT,storage_source_id INT,enable BOOLEAN,root_path TEXT,quota_bytes BIGINT,quota_revision BIGINT DEFAULT 0,PRIMARY KEY(user_id,storage_source_id))");
        jdbc.update("INSERT INTO user_storage_source(user_id,storage_source_id,enable,root_path,quota_bytes) VALUES(10,31,1,'/members/u10',10)");
        users = mock(UserService.class); User actor = new User(); actor.setId(10); actor.setRole(UserRoleEnum.USER);
        when(users.requireLoginUser(10)).thenReturn(actor);
        grants = mock(UserStorageSourceMapper.class);
        when(grants.getByUserIdAndStorageId(10,31)).thenAnswer(call -> {
            var row = jdbc.queryForMap("SELECT * FROM user_storage_source WHERE user_id=10 AND storage_source_id=31");
            UserStorageSource result = new UserStorageSource(); result.setRootPath((String) row.get("root_path")); result.setEnable(((Number)row.get("enable")).intValue()==1);
            result.setQuotaBytes(((Number)row.get("quota_bytes")).longValue()); return result;
        });
        LocalParam param = new LocalParam(); ReflectionTestUtils.setField(param,"filePath",root.resolve("cloud").toString());
        personal = root.resolve("cloud/members/u10"); Files.createDirectories(personal);
        driver = mock(LocalServiceImpl.class); when(driver.getParam()).thenReturn(param); when(driver.getStorageId()).thenReturn(31); when(driver.getCurrentUserBasePath()).thenReturn("/members/u10");
        quota = instance();
    }
    MemberStorageQuotaService instance() { return new MemberStorageQuotaService(jdbc,new DataSourceTransactionManager(data),users,grants); }
    @Test void actualBytesIncludingHiddenFilesCountAndDeletionFreesCapacity() throws Throwable {
        Files.write(personal.resolve(".hidden"),new byte[7]);
        assertThrows(BizException.class, () -> quota.write(10,driver,4,()-> { fail("over-capacity work ran"); return null; }));
        quota.write(10,driver,3,()-> Files.write(personal.resolve("ok"),new byte[3]));
        assertEquals(10,quota.measure(driver,"/members/u10","/"));
        quota.write(10,driver,0,()-> Files.deleteIfExists(personal.resolve(".hidden")));
        quota.write(10,driver,7,()-> Files.write(personal.resolve("next"),new byte[7]));
        assertEquals(10,quota.measure(driver,"/members/u10","/"));
    }
    @Test void twoInstancesCannotBothSpendTheSameRemainingCapacity() throws Exception {
        var another = instance();
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> {
                start.await();
                try { (i==0 ? quota : another).write(10,driver,6,()-> { Files.write(personal.resolve("file"+i),new byte[6]); Thread.sleep(80); return null; }); return true; }
                catch (BizException denied) { return false; }
                catch (Throwable failure) { throw new RuntimeException(failure); }
            })).toList();
            start.countDown(); int successes=0; for(var future:futures) if(future.get()) successes++;
            assertEquals(1,successes); assertEquals(6,quota.measure(driver,"/members/u10","/"));
        }
    }
    @Test void revocationUnsafeRootAndZeroQuotaFailBeforeStorageWrite() {
        jdbc.update("UPDATE user_storage_source SET enable=0");
        assertThrows(BizException.class,()->quota.write(10,driver,1,()->fail("disabled write")));
        jdbc.update("UPDATE user_storage_source SET enable=1,root_path='/members/u100'");
        assertThrows(BizException.class,()->quota.write(10,driver,1,()->fail("other member write")));
        jdbc.update("UPDATE user_storage_source SET root_path='/members/u10',quota_bytes=0");
        assertThrows(BizException.class,()->quota.write(10,driver,1,()->fail("zero limit write")));
    }
    @Test void unknownProvidersAndSymlinksAreNotCountedAsEmpty() throws Exception {
        var unknown = mock(im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService.class);
        assertThrows(BizException.class,()->quota.measure(unknown,"/members/u10","/"));
        Path link=personal.resolve("link");
        try { Files.createSymbolicLink(link,root); } catch (IOException|UnsupportedOperationException denied) { return; }
        assertThrows(BizException.class,()->quota.measure(driver,"/members/u10","/"));
    }
    @Test void streamRefusesForgedSizeAndPrematureEof() throws Exception {
        assertArrayEquals(new byte[]{1,2},MemberStorageQuotaService.bounded(new ByteArrayInputStream(new byte[]{1,2}),2L).readAllBytes());
        assertThrows(IOException.class,()->MemberStorageQuotaService.bounded(new ByteArrayInputStream(new byte[8]),2L).readAllBytes());
        assertThrows(EOFException.class,()->MemberStorageQuotaService.bounded(new ByteArrayInputStream(new byte[1]),2L).readAllBytes());
        assertThrows(BizException.class,()->MemberStorageQuotaService.bounded(InputStream.nullInputStream(),-1L));
    }
    @Test void rootBoundariesDoNotConfuseMemberTenAndMemberOneHundred() {
        assertTrue(MemberStoragePaths.personal("/team/members/u10/report",10));
        assertFalse(MemberStoragePaths.personal("/team/members/u100",10));
        assertThrows(BizException.class,()->MemberStoragePaths.personal("/team/../members/u10",10));
        assertEquals("/team/members/u10",MemberStoragePaths.personalRoot("/team/",10));
    }
}

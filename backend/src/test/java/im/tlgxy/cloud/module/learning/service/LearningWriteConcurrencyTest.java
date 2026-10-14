package im.tlgxy.cloud.module.learning.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import im.tlgxy.cloud.module.learning.mapper.*;
import im.tlgxy.cloud.module.learning.model.*;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningWriteConcurrencyTest {
    private final LearningCourseMapper courses = mock(LearningCourseMapper.class);
    private final LearningMaterialMapper materials = mock(LearningMaterialMapper.class);
    private final LearningFileReader reader = mock(LearningFileReader.class);
    private final AiAttachmentService extractor = mock(AiAttachmentService.class);
    private final UserService users = mock(UserService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final LearningLibraryService library = new LearningLibraryService(courses, materials, reader, extractor, users, transactions);
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ThreadLocal<Boolean> transactionActive = ThreadLocal.withInitial(() -> false);
    private final byte[] text = "课程资料".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "learning-write-test");
        TableInfoHelper.initTableInfo(assistant, LearningCourse.class);
        TableInfoHelper.initTableInfo(assistant, LearningMaterial.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            User user = new User(); user.setId(call.getArgument(0)); user.setEnable(true); user.setRole(UserRoleEnum.USER); return user;
        });
        when(courses.selectOne(any(Wrapper.class))).thenReturn(new LearningCourse());
        when(materials.selectCount(any())).thenReturn(0L);
        when(reader.read(anyString(), anyString())).thenReturn(text);
        when(reader.fingerprint(anyString(), anyString())).thenReturn(LearningLibraryService.fingerprint(text));
        when(extractor.extractLearningContent(any(), anyString())).thenAnswer(call -> {
            assertFalse(transactionActive.get(), "Parsing must not keep the DB transaction open");
            return extraction("课程资料");
        });
        when(transactions.execute(any())).thenAnswer(call -> {
            assertFalse(transactionActive.get());
            transactionActive.set(true);
            try { return ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null); }
            finally { transactionActive.remove(); }
        });
    }

    @AfterEach void close() { pool.shutdownNow(); }

    @Test void slowParsingDoesNotBlockAnotherUsersIndexAndParsingIsOutsideTransactions() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(extractor.extractLearningContent(any(), eq("slow.txt"))).thenAnswer(call -> {
            assertFalse(transactionActive.get());
            started.countDown();
            assertTrue(release.await(4, TimeUnit.SECONDS));
            return extraction("slow material");
        });
        var slow = pool.submit(() -> as(7, () -> library.add(request("/slow.txt"))));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNotNull(pool.submit(() -> as(8, () -> library.add(request("/fast.txt")))).get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone());
        } finally { release.countDown(); }
        assertNotNull(slow.get(2, TimeUnit.SECONDS));
        verify(materials, times(2)).insert(any(LearningMaterial.class));
    }

    @Test void sourcePermissionOrContentChangedDuringParsingCannotBeCommitted() {
        when(reader.fingerprint(anyString(), anyString())).thenReturn("changed");
        assertThrows(BizException.class, () -> as(7, () -> library.add(request("/changed.txt"))));
        when(reader.fingerprint(anyString(), anyString())).thenThrow(new BizException("权限已收回"));
        assertThrows(BizException.class, () -> as(7, () -> library.add(request("/revoked.txt"))));
        verify(materials, never()).insert(any(LearningMaterial.class));
        verifyNoInteractions(transactions);
    }

    @Test void simultaneousSameOwnerImportsRecheckDuplicateAfterParsing() throws Exception {
        var bothParsing = new CountDownLatch(2);
        AtomicInteger inserted = new AtomicInteger();
        when(extractor.extractLearningContent(any(), anyString())).thenAnswer(call -> {
            bothParsing.countDown();
            assertTrue(bothParsing.await(3, TimeUnit.SECONDS));
            return extraction("content");
        });
        when(materials.selectCount(any())).thenAnswer(call -> (long) inserted.get());
        when(materials.insert(any(LearningMaterial.class))).thenAnswer(call -> { inserted.incrementAndGet(); return 1; });
        Callable<Boolean> add = () -> as(7, () -> {
            try { library.add(request("/same.txt")); return true; }
            catch (BizException e) { return false; }
        });
        var first = pool.submit(add);
        var second = pool.submit(add);
        assertNotEquals(first.get(4, TimeUnit.SECONDS), second.get(4, TimeUnit.SECONDS));
        assertEquals(1, inserted.get());
    }

    @Test void ownerWriteLockIsHeldUntilTransactionTemplateFinishesCommit() throws Exception {
        var committing = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        AtomicInteger transactionCalls = new AtomicInteger();
        doAnswer(call -> {
            int count = transactionCalls.incrementAndGet();
            Object result = ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null);
            if (count == 1) {
                committing.countDown();
                assertTrue(releaseCommit.await(4, TimeUnit.SECONDS));
            }
            return result;
        }).when(transactions).execute(any());
        var first = pool.submit(() -> as(7, () -> library.saveCourse(1, new LearningRequests.Course("one", "2026"))));
        try {
            assertTrue(committing.await(2, TimeUnit.SECONDS));
            var secondStarted = new CountDownLatch(1);
            var second = pool.submit(() -> { secondStarted.countDown(); return as(7, () -> library.saveCourse(1, new LearningRequests.Course("two", "2026"))); });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, transactionCalls.get());
            releaseCommit.countDown();
            second.get(2, TimeUnit.SECONDS);
        } finally { releaseCommit.countDown(); }
        first.get(2, TimeUnit.SECONDS);
        assertEquals(2, transactionCalls.get());
    }

    private static LearningRequests.Material request(String path) { return new LearningRequests.Material(1, "local", path, ""); }
    private static AiAttachmentService.LearningExtraction extraction(String text) {
        return new AiAttachmentService.LearningExtraction(text, text.length(), null, null, false, "已索引");
    }
    private static <T> T as(int owner, Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        BackgroundActorContext.runAs(owner, () -> value.set(action.get()));
        return value.get();
    }
}

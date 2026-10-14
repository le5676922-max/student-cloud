package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskExecutorTest {
    @Test void boundedAdmissionAndCompletedJobsReleaseCapacity() throws Exception {
        var executor = new AgentTaskExecutor(1, 1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try {
            executor.reserve();
            executor.submit(() -> { started.countDown(); await(release); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.reserve();
            executor.submit(finished::countDown);
            assertThrows(BizException.class, executor::reserve);
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            executor.reserve();
            executor.release();
        } finally { release.countDown(); executor.close(); }
        assertThrows(BizException.class, executor::reserve);
    }

    @Test void ownerScopeDoesNotInheritOrLeakAfterAnExceptionOnReusedWorker() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            var failure = pool.submit(() -> BackgroundActorContext.runAs(7, () -> {
                assertEquals(7, TlgxyCloudAuthUtil.getCurrentUserId());
                assertThrows(IllegalStateException.class, () -> BackgroundActorContext.runAs(8, () -> {}));
                var childActor = new AtomicReference<Integer>();
                Thread child = new Thread(() -> childActor.set(BackgroundActorContext.currentActor()));
                child.start();
                try { child.join(2000); } catch (InterruptedException e) { throw new RuntimeException(e); }
                assertNull(childActor.get());
                throw new IllegalStateException("simulated failure");
            }));
            assertThrows(ExecutionException.class, () -> failure.get(2, TimeUnit.SECONDS));
            assertNull(pool.submit(BackgroundActorContext::currentActor).get(2, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> BackgroundActorContext.runAs(2, () -> {}));
        } finally { pool.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

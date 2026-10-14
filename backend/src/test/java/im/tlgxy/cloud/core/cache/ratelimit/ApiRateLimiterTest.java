package im.tlgxy.cloud.core.cache.ratelimit;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiRateLimiterTest {
    @Test
    void concurrentFirstHitsNeverCreateExtraWindows() throws Exception {
        var limiter = new ApiRateLimiter(null, "test:", false, 100);
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int index = 0; index < 200; index++) executor.submit(() -> {
                try { start.await(); if (limiter.allow("endpoint", "ip", 10, 60_000)) accepted.incrementAndGet(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            start.countDown();
        }
        assertEquals(10, accepted.get());
    }

    @Test
    void localCapacityDoesNotEvictLiveSecurityCountersAndExpiryIsFixed() {
        var clock = new AtomicLong(0);
        var limiter = new ApiRateLimiter(null, "test:", false, 100, clock::get);
        assertTrue(limiter.allow("one", "ip", 2, 1000));
        clock.set(999);
        assertTrue(limiter.allow("one", "ip", 2, 1000));
        assertFalse(limiter.allow("one", "ip", 2, 1000));
        for (int index = 0; index < 99; index++) assertTrue(limiter.allow("other", "ip" + index, 1, 1000));
        assertFalse(limiter.allow("overflow", "new-ip", 1, 1000));
        assertFalse(limiter.allow("one", "ip", 2, 1000));
        clock.set(1000);
        assertTrue(limiter.allow("one", "ip", 2, 1000));
        clock.set(2000);
        assertTrue(limiter.allow("overflow", "new-ip", 1, 1000));
    }

    @Test
    void fullMethodAndSubjectHaveIndependentLimits() {
        var limiter = new ApiRateLimiter(null, "test:", false, 100);
        assertTrue(limiter.allow("ControllerA.login(String)", "ip", 1, 1000));
        assertFalse(limiter.allow("ControllerA.login(String)", "ip", 1, 1000));
        assertTrue(limiter.allow("ControllerB.login(String)", "ip", 1, 1000));
        assertTrue(limiter.allow("ControllerA.login(int)", "ip", 1, 1000));
        assertTrue(limiter.allow("ControllerA.login(String)", "other-ip", 1, 1000));
    }

    @Test
    void redisUsesOneAtomicScriptAndDoesNotExposeIpOrMailboxInKeys() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(ApiRateLimiter.SCRIPT), anyList(), anyString())).thenReturn(1L, 2L, 3L);
        var limiter = new ApiRateLimiter(redis, "isolated:", false, 100);
        assertTrue(limiter.allow("mail", "private@example.invalid", 2, 60_000));
        assertTrue(limiter.allow("mail", "private@example.invalid", 2, 60_000));
        assertFalse(limiter.allow("mail", "private@example.invalid", 2, 60_000));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(3)).execute(eq(ApiRateLimiter.SCRIPT), keys.capture(), eq("60000"));
        assertTrue(keys.getValue().getFirst().matches("isolated:[a-f0-9]{64}"));
        assertFalse(keys.getValue().getFirst().contains("private"));
        assertTrue(ApiRateLimiter.SCRIPT.getScriptAsString().contains("PEXPIRE"));
        assertTrue(ApiRateLimiter.SCRIPT.getScriptAsString().contains("INCR"));
    }

    @Test
    void sharedRedisFailureIsClosedByDefaultAndProbeBackoffIsBounded() {
        var redis = mock(StringRedisTemplate.class);
        var clock = new AtomicLong(0);
        when(redis.execute(eq(ApiRateLimiter.SCRIPT), anyList(), anyString())).thenThrow(new IllegalStateException("secret-password"));
        var limiter = new ApiRateLimiter(redis, "test:", false, 100, clock::get);
        var failure = assertThrows(BizException.class, () -> limiter.allow("one", "ip", 10, 1000));
        assertFalse(failure.getMessage().contains("secret"));
        assertThrows(BizException.class, () -> limiter.allow("two", "ip", 10, 1000));
        verify(redis, times(1)).execute(any(), anyList(), anyString());
        clock.set(1000);
        assertThrows(BizException.class, () -> limiter.allow("one", "ip", 10, 1000));
        verify(redis, times(2)).execute(any(), anyList(), anyString());
    }

    @Test
    void explicitlyChosenLocalFallbackStillLimitsAndBadPolicyIsRejected() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(ApiRateLimiter.SCRIPT), anyList(), anyString())).thenThrow(new IllegalStateException());
        var limiter = new ApiRateLimiter(redis, "test:", true, 100);
        assertTrue(limiter.allow("one", "ip", 1, 1000));
        assertFalse(limiter.allow("one", "ip", 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> limiter.allow("one", "ip", 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> limiter.allow("one", "ip", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.allow("one", "ip", 1, 86_400_001));
    }
}

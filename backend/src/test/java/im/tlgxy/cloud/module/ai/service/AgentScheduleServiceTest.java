package im.tlgxy.cloud.module.ai.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import im.tlgxy.cloud.core.exception.core.BizException;
import static org.junit.jupiter.api.Assertions.*;

class AgentScheduleServiceTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test void nextRunUsesShanghaiTimeAndDoesNotReplayTheSameMinute() {
        long sundayBefore = ZonedDateTime.of(2026, 9, 27, 20, 59, 0, 0, SHANGHAI).toInstant().toEpochMilli();
        long sundayAt = ZonedDateTime.of(2026, 9, 27, 21, 0, 0, 0, SHANGHAI).toInstant().toEpochMilli();
        assertEquals(sundayAt, AgentScheduleService.nextRun(sundayBefore, 7, 21, 0));
        assertEquals(sundayAt + Duration.ofDays(7).toMillis(), AgentScheduleService.nextRun(sundayAt, 7, 21, 0));
    }

    @Test void backoffIsLimitedAndDoesNotRetryPermissionOrUnknownFailures() {
        long now = 1000;
        assertEquals(61000L, AgentScheduleService.retryAt(new java.net.SocketTimeoutException(), 0, now));
        assertEquals(301000L, AgentScheduleService.retryAt(new java.net.ConnectException(), 1, now));
        assertNull(AgentScheduleService.retryAt(new java.net.SocketTimeoutException(), 2, now));
        assertNull(AgentScheduleService.retryAt(new BizException("权限失效"), 0, now));
        assertNull(AgentScheduleService.retryAt(new IllegalStateException("unknown"), 0, now));
    }

    @Test void persistedOccurrenceIdIsStableButChangesForNextWeek() {
        assertEquals(AgentScheduleService.occurrenceId("rule", 1000), AgentScheduleService.occurrenceId("rule", 1000));
        assertNotEquals(AgentScheduleService.occurrenceId("rule", 1000), AgentScheduleService.occurrenceId("rule", 2000));
        assertNotEquals(AgentScheduleService.occurrenceId("rule", 1000), AgentScheduleService.occurrenceId("other", 1000));
    }
}

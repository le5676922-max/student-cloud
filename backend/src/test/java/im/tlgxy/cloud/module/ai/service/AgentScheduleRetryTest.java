package im.tlgxy.cloud.module.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.mapper.AgentScheduleMapper;
import im.tlgxy.cloud.module.ai.model.automation.*;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import im.tlgxy.cloud.module.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.beans.BeanWrapperImpl;

import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real CAS SQL against a private in-memory SQLite database; no application datasource is used. */
class AgentScheduleRetryTest {
    private final AgentScheduleMapper mapper = mock(AgentScheduleMapper.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final UserService users = mock(UserService.class);
    private final AgentScheduleService service = new AgentScheduleService(mapper, tasks,
            mock(AutomationFileService.class), mock(LearningLibraryService.class), users);
    private Connection db;
    private MockedStatic<TlgxyCloudAuthUtil> auth;
    private static final long NOW = System.currentTimeMillis();

    @BeforeEach void setup() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "schedule-cas-test"), AgentSchedule.class);
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement sql = db.createStatement()) {
            sql.execute(Files.readString(Path.of("src/main/resources/db/migration-sqlite/V63__agent_schedules.sql")));
            for (String statement : Files.readString(Path.of("src/main/resources/db/migration-sqlite/V70__agent_schedule_preview_retries.sql")).split(";"))
                if (!statement.isBlank()) sql.execute(statement);
            sql.execute("INSERT INTO ai_schedule(id,owner_user_id,title,task_type,storage_key,directory_path,day_of_week,hour_of_day,minute_of_hour,enabled,next_run_at) VALUES('rule',7,'rule','ORGANIZE_FILES','local','/',7,21,0,1," + (NOW - 1000) + ")");
        }
        auth = mockStatic(TlgxyCloudAuthUtil.class);
        auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(7);
        when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(call -> update(call.getArgument(1)));
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            var rows = select(call.getArgument(0)); return rows.isEmpty() ? null : rows.getFirst();
        });
        when(tasks.previewForSchedule(any(), anyString())).thenAnswer(call -> view(call.getArgument(1), "PENDING"));
    }
    @AfterEach void close() throws Exception { auth.close(); db.close(); }

    @Test void transientFailureRetriesSameOccurrenceAndPreservesWeeklyTime() throws Exception {
        when(tasks.previewForSchedule(any(), anyString())).thenThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary"))
                .thenAnswer(call -> view(call.getArgument(1), "PENDING"));
        service.generateOne(row(), NOW);
        AgentSchedule failed = row();
        assertEquals(NOW + 60000, failed.getRetryAt());
        assertEquals(1, failed.getRetryCount());
        long weekly = failed.getNextRunAt();
        String occurrence = failed.getOccurrenceTaskId();
        service.generateOne(failed, NOW + 60000);
        AgentSchedule recovered = row();
        assertEquals(weekly, recovered.getNextRunAt());
        assertEquals(occurrence, recovered.getLastTaskId());
        assertNull(recovered.getRetryAt());
        assertNull(recovered.getLastError());
        verify(tasks, times(2)).previewForSchedule(any(), eq(occurrence));
        verify(tasks, never()).execute(anyString(), anyBoolean());
    }

    @Test void persistedPreviewSurvivesScheduleStateWriteFailureWithoutCreatingDuplicateTask() throws Exception {
        Map<String, Automation.View> persisted = new HashMap<>();
        var created = new java.util.concurrent.atomic.AtomicInteger();
        var failStateWrite = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(tasks.previewForSchedule(any(), anyString())).thenAnswer(call -> persisted.computeIfAbsent(call.getArgument(1), id -> {
            created.incrementAndGet(); return view(id, "PENDING");
        }));
        when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
            if (created.get() > 0 && failStateWrite.compareAndSet(true, false))
                throw new org.springframework.dao.TransientDataAccessResourceException("state write failed");
            return update(call.getArgument(1));
        });
        service.generateOne(row(), NOW);
        AgentSchedule failed = row();
        assertEquals(1, created.get()); assertEquals(NOW + 60000, failed.getRetryAt());
        assertNull(failed.getLastTaskId()); assertNotNull(failed.getOccurrenceTaskId());
        // A new service instance represents recovery after an application restart.
        var restarted = new AgentScheduleService(mapper, tasks, mock(AutomationFileService.class), mock(LearningLibraryService.class), users);
        restarted.generateOne(failed, NOW + 60000);
        assertEquals(1, created.get()); assertEquals(failed.getOccurrenceTaskId(), row().getLastTaskId());
        assertEquals(failed.getNextRunAt(), row().getNextRunAt()); assertNull(row().getRetryAt());
        verify(tasks, never()).execute(anyString(), anyBoolean());
    }

    @Test void permanentPermissionFailureNeverRetriesAndDoesNotLeakExceptionDetail() throws Exception {
        when(tasks.previewForSchedule(any(), anyString())).thenThrow(new BizException("private path and secret"));
        service.generateOne(row(), NOW);
        assertNull(row().getRetryAt());
        assertFalse(row().getLastError().contains("secret"));
        service.generateOne(row(), NOW + 60000);
        verify(tasks, times(1)).previewForSchedule(any(), anyString());
    }

    @Test void retriesStopAfterTwoBackoffs() throws Exception {
        when(tasks.previewForSchedule(any(), anyString())).thenThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary"));
        service.generateOne(row(), NOW);
        service.generateOne(row(), NOW + 60000);
        assertEquals(NOW + 360000, row().getRetryAt());
        service.generateOne(row(), NOW + 360000);
        assertNull(row().getRetryAt());
        service.generateOne(row(), NOW + 500000);
        verify(tasks, times(3)).previewForSchedule(any(), anyString());
    }

    @Test void losingWeeklyClaimOrPausedRuleDoesNotCreateDuplicatePreview() throws Exception {
        AgentSchedule stale = row();
        service.generateOne(stale, NOW);
        service.generateOne(stale, NOW);
        verify(tasks, times(1)).previewForSchedule(any(), anyString());
        try (Statement sql = db.createStatement()) { sql.execute("UPDATE ai_schedule SET enabled=0,next_run_at=" + (NOW - 1)); }
        service.generateOne(row(), NOW);
        verify(tasks, times(1)).previewForSchedule(any(), anyString());
    }

    @Test void manualPreviewReusesLiveTaskAndDoesNotChangeWeeklyDate() throws Exception {
        service.generateOne(row(), NOW);
        AgentSchedule created = row();
        when(tasks.get(created.getLastTaskId())).thenReturn(view(created.getLastTaskId(), "PENDING"));
        var result = service.previewNow("rule");
        assertEquals(created.getLastTaskId(), result.task().id());
        assertEquals(created.getNextRunAt(), row().getNextRunAt());
        verify(tasks, never()).execute(anyString(), anyBoolean());
    }

    @Test void manualEndedTaskGeneratesFreshPreviewAndUnknownResultReusesPersistedOccurrence() throws Exception {
        service.generateOne(row(), NOW);
        AgentSchedule created = row();
        when(tasks.get(created.getLastTaskId())).thenReturn(view(created.getLastTaskId(), "EXPIRED"));
        var result = service.previewNow("rule");
        assertNotEquals(created.getLastTaskId(), result.task().id());
        assertEquals(created.getNextRunAt(), row().getNextRunAt());
        try (Statement sql = db.createStatement()) { sql.execute("UPDATE ai_schedule SET last_task_id=NULL,last_error='uncertain'"); }
        var recovered = service.previewNow("rule");
        assertEquals(result.task().id(), recovered.task().id());
    }

    @Test void currentAccountAndOwnershipAreCheckedBeforeManualPreview() throws Exception {
        auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(8);
        assertThrows(BizException.class, () -> service.previewNow("rule"));
        verifyNoInteractions(tasks);
        auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(7);
        when(users.requireLoginUser(7)).thenThrow(new BizException("disabled"));
        assertThrows(BizException.class, () -> service.previewNow("rule"));
        verifyNoInteractions(tasks);
    }

    @Test void activeClaimIsNotStealableButExpiredUncertainClaimIsManuallyRecoverable() throws Exception {
        String task = UUID.randomUUID().toString();
        try (Statement sql = db.createStatement()) {
            sql.execute("UPDATE ai_schedule SET next_run_at=" + (NOW + 86400000) + ",claim_token='claim',claim_until=" + (NOW + 600000) + ",occurrence_task_id='" + task + "'");
        }
        assertThrows(BizException.class, () -> service.previewNow("rule"));
        verifyNoInteractions(tasks);
        try (Statement sql = db.createStatement()) { sql.execute("UPDATE ai_schedule SET claim_until=1"); }
        assertEquals(task, service.previewNow("rule").task().id());
    }

    @Test void concurrentWorkerCannotClaimAStillRunningPreview() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(tasks.previewForSchedule(any(), anyString())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS)); return view(call.getArgument(1), "PENDING");
        });
        AgentSchedule first = row(), second = row();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var worker = pool.submit(() -> service.generateOne(first, NOW));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.generateOne(second, NOW);
            release.countDown(); worker.get(2, TimeUnit.SECONDS);
            verify(tasks, times(1)).previewForSchedule(any(), anyString());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private AgentSchedule row() throws SQLException { return selectSql("SELECT * FROM ai_schedule", List.of()).getFirst(); }
    private synchronized int update(LambdaUpdateWrapper<AgentSchedule> wrapper) throws SQLException {
        var prepared = bind("UPDATE ai_schedule SET " + wrapper.getSqlSet() + " WHERE " + wrapper.getSqlSegment(), wrapper.getParamNameValuePairs());
        try (PreparedStatement query = db.prepareStatement(prepared.sql)) {
            for (int i = 0; i < prepared.args.size(); i++) query.setObject(i + 1, prepared.args.get(i));
            return query.executeUpdate();
        }
    }
    private synchronized List<AgentSchedule> select(Wrapper<AgentSchedule> wrapper) throws SQLException {
        var params = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<AgentSchedule, ?, ?>) wrapper).getParamNameValuePairs();
        var prepared = bind("SELECT * FROM ai_schedule WHERE " + wrapper.getSqlSegment(), params);
        return selectSql(prepared.sql, prepared.args);
    }
    private synchronized List<AgentSchedule> selectSql(String sql, List<Object> args) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) query.setObject(i + 1, args.get(i));
            try (ResultSet result = query.executeQuery()) {
                List<AgentSchedule> rows = new ArrayList<>();
                while (result.next()) {
                    AgentSchedule schedule = new AgentSchedule(); BeanWrapperImpl bean = new BeanWrapperImpl(schedule);
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        String field = result.getMetaData().getColumnName(i);
                        StringBuilder property = new StringBuilder(); boolean upper = false;
                        for (char character : field.toCharArray()) {
                            if (character == '_') upper = true;
                            else { property.append(upper ? Character.toUpperCase(character) : character); upper = false; }
                        }
                        Object value = result.getObject(i);
                        if (bean.getPropertyType(property.toString()) == Boolean.class && value instanceof Number number) value = number.intValue() != 0;
                        bean.setPropertyValue(property.toString(), value);
                    }
                    rows.add(schedule);
                }
                return rows;
            }
        }
    }
    private record Prepared(String sql, List<Object> args) { }
    private static Prepared bind(String sql, Map<String, Object> values) {
        Matcher matcher = Pattern.compile("#\\{ew.paramNameValuePairs.([^}]+)}").matcher(sql);
        List<Object> args = new ArrayList<>(); StringBuffer result = new StringBuffer();
        while (matcher.find()) { args.add(values.get(matcher.group(1))); matcher.appendReplacement(result, "?"); }
        matcher.appendTail(result); return new Prepared(result.toString(), args);
    }
    private static Automation.View view(String id, String status) {
        return new Automation.View(id, "preview", status, List.of(), List.of(), NOW, NOW + 600000, "confirmation required");
    }
}

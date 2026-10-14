package im.tlgxy.cloud.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.mapper.AgentScheduleMapper;
import im.tlgxy.cloud.module.ai.model.automation.AgentSchedule;
import im.tlgxy.cloud.module.ai.model.automation.AgentScheduleRequest;
import im.tlgxy.cloud.module.ai.model.automation.Automation;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Weekly schedules create fresh confirmation previews; they never execute file writes unattended. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentScheduleService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long CLAIM_MILLIS = 300_000;
    private static final long[] RETRY_DELAYS = {60_000, 300_000};
    private final AgentScheduleMapper schedules;
    private final AgentTaskService tasks;
    private final AutomationFileService files;
    private final LearningLibraryService learning;
    private final UserService users;

    public List<AgentSchedule> list() {
        return schedules.selectList(owned(owner()).orderByDesc(AgentSchedule::getNextRunAt)).stream()
                .map(this::withState).toList();
    }

    public AgentSchedule create(AgentScheduleRequest request) {
        int current = owner();
        if (request == null || request.type() == null || !Set.of("ORGANIZE_FILES", "IMPORT_LEARNING", "RENAME_BY_DATE").contains(request.type())
                || request.title() == null || request.title().isBlank() || request.title().length() > 100
                || request.storageKey() == null || request.storageKey().isBlank() || request.storageKey().length() > 100
                || (request.tags() != null && request.tags().length() > 200)
                || request.dayOfWeek() == null || request.dayOfWeek() < 1 || request.dayOfWeek() > 7
                || request.hourOfDay() == null || request.hourOfDay() < 0 || request.hourOfDay() > 23
                || request.minuteOfHour() == null || request.minuteOfHour() < 0 || request.minuteOfHour() > 59)
            throw new BizException("定时规则参数不正确");
        if (schedules.selectCount(owned(current)) >= 10) throw new BizException("每个账号最多创建 10 条定时规则");
        String directory = AutomationFileService.path(request.path());
        switch (request.type()) {
            case "ORGANIZE_FILES" -> files.list(request.storageKey(), directory, true);
            case "RENAME_BY_DATE" -> files.listForRename(request.storageKey(), directory);
            case "IMPORT_LEARNING" -> {
                if (request.courseId() == null || request.courseId() <= 0) throw new BizException("请选择自己的课程");
                files.list(request.storageKey(), directory, false);
                learning.materials(request.courseId());
            }
            default -> throw new BizException("不支持的定时任务");
        }
        AgentSchedule schedule = new AgentSchedule();
        schedule.setId(UUID.randomUUID().toString());
        schedule.setOwnerUserId(current);
        schedule.setTitle(request.title().trim());
        schedule.setTaskType(request.type());
        schedule.setStorageKey(request.storageKey());
        schedule.setDirectoryPath(directory);
        schedule.setCourseId(request.courseId());
        schedule.setTags(request.tags());
        schedule.setDayOfWeek(request.dayOfWeek());
        schedule.setHourOfDay(request.hourOfDay());
        schedule.setMinuteOfHour(request.minuteOfHour());
        schedule.setEnabled(true);
        schedule.setRetryCount(0);
        schedule.setNextRunAt(nextRun(System.currentTimeMillis(), request.dayOfWeek(), request.hourOfDay(), request.minuteOfHour()));
        schedules.insert(schedule);
        return withState(schedule);
    }

    public AgentSchedule setEnabled(String id, boolean enabled) {
        int current = owner();
        AgentSchedule old = require(id, current);
        if (Boolean.valueOf(enabled).equals(old.getEnabled())) return withState(old);
        long next = enabled ? nextRun(System.currentTimeMillis(), old.getDayOfWeek(), old.getHourOfDay(), old.getMinuteOfHour()) : old.getNextRunAt();
        schedules.update(null, new LambdaUpdateWrapper<AgentSchedule>().eq(AgentSchedule::getId, id)
                .eq(AgentSchedule::getOwnerUserId, current).set(AgentSchedule::getEnabled, enabled)
                .set(AgentSchedule::getNextRunAt, next));
        return withState(require(id, current));
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void generateDuePreviews() {
        long now = System.currentTimeMillis();
        List<AgentSchedule> due = schedules.selectList(new LambdaQueryWrapper<AgentSchedule>()
                .eq(AgentSchedule::getEnabled, true)
                .and(w -> w.le(AgentSchedule::getNextRunAt, now).or().le(AgentSchedule::getRetryAt, now))
                .orderByAsc(AgentSchedule::getNextRunAt).last("LIMIT 50"));
        for (AgentSchedule schedule : due) {
            try { generateOne(schedule, now); }
            catch (Exception e) {
                // Never emit provider messages, paths or credentials into scheduler logs.
                log.warn("Scheduled preview state could not be recorded: {} ({})", schedule.getId(), e.getClass().getSimpleName());
            }
        }
    }

    void generateOne(AgentSchedule schedule, long now) {
        boolean weekly = schedule.getNextRunAt() <= now;
        if (!weekly && (schedule.getRetryAt() == null || schedule.getRetryAt() > now)) return;
        if (!weekly && schedule.getOccurrenceTaskId() == null) return;
        String taskId = weekly ? occurrenceId(schedule.getId(), schedule.getNextRunAt()) : schedule.getOccurrenceTaskId();
        String token = UUID.randomUUID().toString();
        var claim = availableClaim(schedule, now).eq(AgentSchedule::getEnabled, true);
        if (weekly) {
            claim.eq(AgentSchedule::getNextRunAt, schedule.getNextRunAt())
                    .set(AgentSchedule::getNextRunAt, nextRun(now, schedule.getDayOfWeek(), schedule.getHourOfDay(), schedule.getMinuteOfHour()))
                    .set(AgentSchedule::getLastRunAt, now).set(AgentSchedule::getRetryCount, 0)
                    .set(AgentSchedule::getLastTaskId, null);
        } else claim.eq(AgentSchedule::getRetryAt, schedule.getRetryAt());
        claim.set(AgentSchedule::getClaimToken, token).set(AgentSchedule::getClaimUntil, now + CLAIM_MILLIS)
                .set(AgentSchedule::getOccurrenceTaskId, taskId).set(AgentSchedule::getRetryAt, null)
                .set(AgentSchedule::getLastError, null);
        if (schedules.update(null, claim) != 1) return;
        generateClaimed(schedule, now, taskId, token, weekly ? 0 : retryCount(schedule));
    }

    /** Explicit user action. Reuse a live/uncertain preview; only a known ended preview gets a new ID. */
    public PreviewResult previewNow(String id) {
        int current = owner();
        AgentSchedule schedule = require(id, current);
        long now = System.currentTimeMillis();
        if (Boolean.TRUE.equals(schedule.getEnabled()) && schedule.getNextRunAt() <= now) {
            String dueTaskId = occurrenceId(schedule.getId(), schedule.getNextRunAt());
            generateOne(schedule, now);
            AgentSchedule latest = require(id, current);
            if (dueTaskId.equals(latest.getLastTaskId()))
                return new PreviewResult(withState(latest), tasks.previewForSchedule(request(latest), dueTaskId));
            throw new BizException("本次规则正在生成预览或生成失败，请刷新规则及任务列表查看");
        }
        String taskId = schedule.getOccurrenceTaskId();
        if (schedule.getLastTaskId() != null) {
            var previous = tasks.get(schedule.getLastTaskId());
            if (Set.of("PENDING", "RUNNING").contains(previous.status()))
                return new PreviewResult(withState(schedule), tasks.previewForSchedule(request(schedule), previous.id()));
            taskId = UUID.randomUUID().toString();
        }
        if (taskId == null) taskId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        var claim = availableClaim(schedule, now).eq(AgentSchedule::getNextRunAt, schedule.getNextRunAt());
        sameNullable(claim, AgentSchedule::getOccurrenceTaskId, schedule.getOccurrenceTaskId());
        sameNullable(claim, AgentSchedule::getLastTaskId, schedule.getLastTaskId());
        sameNullable(claim, AgentSchedule::getLastRunAt, schedule.getLastRunAt());
        claim.set(AgentSchedule::getClaimToken, token).set(AgentSchedule::getClaimUntil, now + CLAIM_MILLIS)
                .set(AgentSchedule::getOccurrenceTaskId, taskId).set(AgentSchedule::getLastTaskId, null)
                .set(AgentSchedule::getLastRunAt, now).set(AgentSchedule::getRetryCount, 0)
                .set(AgentSchedule::getRetryAt, null).set(AgentSchedule::getLastError, null);
        if (schedules.update(null, claim) != 1) throw new BizException("规则正在生成预览或已变化，请刷新列表后查看");
        Automation.View view = generateClaimed(schedule, now, taskId, token, 0);
        AgentSchedule latest = withState(require(id, current));
        if (view == null) throw new BizException(latest.getLastError() == null
                ? "本次结果尚不确定，请先刷新任务列表核实" : latest.getLastError());
        return new PreviewResult(latest, view);
    }

    private Automation.View generateClaimed(AgentSchedule schedule, long now, String taskId, String token, int priorRetries) {
        try {
            AtomicReference<Automation.View> result = new AtomicReference<>();
            BackgroundActorContext.runAs(schedule.getOwnerUserId(), () -> {
                users.requireLoginUser(schedule.getOwnerUserId());
                result.set(tasks.previewForSchedule(request(schedule), taskId));
            });
            int recorded = schedules.update(null, claimed(schedule, token)
                    .set(AgentSchedule::getLastTaskId, result.get().id()).set(AgentSchedule::getLastError, null)
                    .set(AgentSchedule::getRetryAt, null).set(AgentSchedule::getClaimToken, null)
                    .set(AgentSchedule::getClaimUntil, null));
            if (recorded != 1) throw new BizException("预览已生成，但规则状态已变化，请刷新任务列表核实");
            return result.get();
        } catch (Exception e) {
            Long retryAt = retryAt(e, priorRetries, now);
            String message = retryAt != null
                    ? "临时连接或服务故障，将有限重试生成预览；不会执行文件操作"
                    : e instanceof BizException
                    ? "预览未生成或权限/规则已变化，请检查目录权限、课程及任务列表后手动重试"
                    : "预览结果尚不确定或重试次数已用完，请先刷新任务列表核实，再手动重新生成";
            schedules.update(null, claimed(schedule, token).set(AgentSchedule::getLastError, message)
                    .set(AgentSchedule::getRetryCount, retryAt == null ? priorRetries : priorRetries + 1)
                    .set(AgentSchedule::getRetryAt, retryAt).set(AgentSchedule::getClaimToken, null)
                    .set(AgentSchedule::getClaimUntil, null));
            return null;
        }
    }

    private static Automation.Request request(AgentSchedule schedule) {
        return new Automation.Request(schedule.getTaskType(), schedule.getStorageKey(),
                schedule.getDirectoryPath(), schedule.getCourseId(), schedule.getTags());
    }

    static String occurrenceId(String scheduleId, long runAt) {
        return UUID.nameUUIDFromBytes((scheduleId + ":" + runAt).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static Long retryAt(Exception error, int priorRetries, long now) {
        // Permission, validation and unknown outcomes require explicit review, never blind retries.
        if (error instanceof BizException || priorRetries >= RETRY_DELAYS.length) return null;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.ConnectException
                    || cause instanceof java.sql.SQLTransientException
                    || cause instanceof org.springframework.dao.TransientDataAccessException)
                return now + RETRY_DELAYS[Math.max(0, priorRetries)];
        }
        return null;
    }

    private static int retryCount(AgentSchedule schedule) { return schedule.getRetryCount() == null ? 0 : schedule.getRetryCount(); }
    private LambdaUpdateWrapper<AgentSchedule> availableClaim(AgentSchedule schedule, long now) {
        return new LambdaUpdateWrapper<AgentSchedule>().eq(AgentSchedule::getId, schedule.getId())
                .eq(AgentSchedule::getOwnerUserId, schedule.getOwnerUserId())
                .and(w -> w.isNull(AgentSchedule::getClaimToken).or().le(AgentSchedule::getClaimUntil, now));
    }
    private LambdaUpdateWrapper<AgentSchedule> claimed(AgentSchedule schedule, String token) {
        return new LambdaUpdateWrapper<AgentSchedule>().eq(AgentSchedule::getId, schedule.getId())
                .eq(AgentSchedule::getOwnerUserId, schedule.getOwnerUserId()).eq(AgentSchedule::getClaimToken, token);
    }
    private <T> void sameNullable(LambdaUpdateWrapper<AgentSchedule> wrapper,
                                  com.baomidou.mybatisplus.core.toolkit.support.SFunction<AgentSchedule, T> field, T value) {
        if (value == null) wrapper.isNull(field); else wrapper.eq(field, value);
    }
    private AgentSchedule withState(AgentSchedule schedule) {
        long now = System.currentTimeMillis();
        schedule.setPreviewState(!Boolean.TRUE.equals(schedule.getEnabled()) ? "PAUSED"
                : schedule.getClaimToken() != null ? (schedule.getClaimUntil() != null && schedule.getClaimUntil() > now ? "GENERATING" : "UNCERTAIN")
                : schedule.getRetryAt() != null ? "WAITING_RETRY"
                : schedule.getLastError() != null ? "FAILED" : schedule.getLastTaskId() != null ? "PREVIEW_CREATED" : "WAITING");
        return schedule;
    }
    public record PreviewResult(AgentSchedule schedule, Automation.View task) { }

    static long nextRun(long afterMillis, int weekday, int hour, int minute) {
        ZonedDateTime after = Instant.ofEpochMilli(afterMillis).atZone(ZONE);
        ZonedDateTime candidate = after.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)))
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!candidate.isAfter(after)) candidate = candidate.plusWeeks(1);
        return candidate.toInstant().toEpochMilli();
    }

    private int owner() {
        int id = TlgxyCloudAuthUtil.getCurrentUserId();
        users.requireLoginUser(id);
        return id;
    }

    private LambdaQueryWrapper<AgentSchedule> owned(int id) {
        return new LambdaQueryWrapper<AgentSchedule>().eq(AgentSchedule::getOwnerUserId, id);
    }

    private AgentSchedule require(String id, int current) {
        AgentSchedule schedule = schedules.selectOne(owned(current).eq(AgentSchedule::getId, id));
        if (schedule == null) throw new BizException("定时规则不存在或无权访问");
        return schedule;
    }

    @EventListener
    public void onUserDeleted(UserDeleteEvent event) { schedules.delete(owned(event.getId())); }
}

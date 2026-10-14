package im.tlgxy.cloud.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.module.ai.mapper.AgentTaskMapper;
import im.tlgxy.cloud.module.ai.model.automation.*;
import im.tlgxy.cloud.module.learning.model.LearningRequests;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import im.tlgxy.cloud.module.learning.service.LearningPathSyncException;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.SmartInitializingSingleton;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTaskService implements SmartInitializingSingleton {
    private final AgentTaskMapper tasks;
    private final AutomationFileService files;
    private final LearningLibraryService learning;
    private final UserService users;
    private final ObjectMapper json;
    private final AgentTaskExecutor executor;
    private static final String NOTICE = "任务仅影响列出的文件；最多扫描当前目录 400 项、每批处理 20 份，不递归。不覆盖、不删除。10 分钟内确认有效，执行时重新鉴权；失败后停止，已完成操作不会自动回滚。本人课程索引会同步新路径；同步异常时请使用资料库的修复路径，不要重复移动文件。";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Shanghai"));

    public Automation.View preview(Automation.Request request) {
        return previewWithId(request, UUID.randomUUID().toString(), false);
    }

    /** Server-only occurrence ID. Replayed schedule attempts never create a second confirmation task. */
    Automation.View previewForSchedule(Automation.Request request, String taskId) {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid schedule occurrence");
        return previewWithId(request, taskId, true);
    }

    private Automation.View previewWithId(Automation.Request request, String taskId, boolean scheduled) {
        int owner = owner();
        if (request == null) throw new BizException("任务参数不合法");
        if (request.type() == null || !Set.of("ORGANIZE_FILES", "IMPORT_LEARNING", "RENAME_BY_DATE").contains(request.type())) throw new BizException("不支持的自动化任务");
        if (request.storageKey() == null || request.storageKey().isBlank() || request.storageKey().length() > 100
                || (request.tags() != null && request.tags().length() > 200)) throw new BizException("任务参数不合法");
        boolean organize = request.type().equals("ORGANIZE_FILES");
        boolean rename = request.type().equals("RENAME_BY_DATE");
        List<AutomationFileService.Entry> scheduledEntries = null;
        Set<String> existing = new HashSet<>();
        if (!organize && !rename) {
            if (request.courseId() == null || request.courseId() <= 0) throw new BizException("请选择自己的课程");
            learning.materials(request.courseId()).forEach(item -> existing.add(item.getStorageKey() + "\n" + item.getPath()));
        }
        if (scheduled) {
            // Always re-resolve source/directory grants even when returning a previously persisted task.
            scheduledEntries = rename ? files.listForRename(request.storageKey(), request.path())
                    : files.list(request.storageKey(), request.path(), organize);
            AgentTask previous = tasks.selectOne(owned(owner).eq(AgentTask::getId, taskId));
            if (previous != null) return view(previous);
        }
        if (tasks.selectCount(owned(owner).eq(AgentTask::getStatus, "PENDING").gt(AgentTask::getExpiresAt, System.currentTimeMillis())) >= 20) {
            throw new BizException("待确认任务过多，请先取消旧任务");
        }
        List<Automation.Step> steps = new ArrayList<>();
        for (var file : scheduledEntries != null ? scheduledEntries
                : rename ? files.listForRename(request.storageKey(), request.path()) : files.list(request.storageKey(), request.path(), organize)) {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            String target = "";
            if (organize) {
                String parent = file.path().substring(0, file.path().lastIndexOf('/') + 1);
                target = parent + category(name) + "/" + name;
                try { files.checkTarget(request.storageKey(), target); } catch (BizException ignored) { continue; }
            } else if (rename) {
                String parent = file.path().substring(0, file.path().lastIndexOf('/') + 1);
                target = parent + datedName(name, file.modifiedAt(), steps.size() + 1);
                try { files.checkRenameTarget(request.storageKey(), target); } catch (BizException ignored) { continue; }
            } else {
                if (!Set.of("txt", "md", "pdf", "docx").contains(extension(name)) || file.size() > 5 * 1024 * 1024
                        || existing.contains(request.storageKey() + "\n" + file.path())) continue;
            }
            steps.add(new Automation.Step(organize ? "MOVE_FILE" : rename ? "RENAME_FILE" : "INDEX_MATERIAL", request.storageKey(), file.path(),
                    target, request.courseId(), request.tags() == null ? "" : request.tags(), file.size(), file.modifiedAt(), file.locationToken()));
            if (steps.size() == 20) break;
        }
        if (steps.isEmpty()) throw new BizException("当前目录没有可处理文件；请检查权限、文件格式、已有索引或同名冲突");
        AgentTask task = new AgentTask();
        task.setId(taskId);
        task.setOwnerUserId(owner);
        task.setTitle(organize ? "按文件类型整理目录" : rename ? "按修改日期批量重命名" : "批量归档到课程 #" + request.courseId());
        task.setStatus("PENDING");
        task.setStepsJson(encode(steps));
        task.setResultsJson("[]");
        task.setCreatedAt(System.currentTimeMillis());
        task.setExpiresAt(task.getCreatedAt() + 600_000);
        try { tasks.insert(task); }
        catch (org.springframework.dao.DuplicateKeyException conflict) {
            if (!scheduled) throw conflict;
            return view(require(taskId, owner));
        }
        return view(task);
    }

    public List<Automation.View> list() {
        return tasks.selectList(owned(owner()).orderByDesc(AgentTask::getCreatedAt).last("LIMIT 30")).stream().map(this::view).toList();
    }

    public AutomationFileService.DuplicateReport duplicates(String storageKey, String path) {
        owner();
        return files.duplicates(storageKey, path);
    }

    public Automation.View get(String id) { return view(require(id, owner())); }

    /** A failed step is re-previewed as a new task; completed or uncertain steps are never replayed. */
    public Automation.View retryFailed(String id) {
        int current = owner();
        AgentTask old = require(id, current);
        if (!Set.of("FAILED", "PARTIAL").contains(old.getStatus())) throw new BizException("只能重试明确失败的步骤");
        List<Automation.Step> original = decodeSteps(old);
        List<Automation.StepResult> results;
        try { results = json.readValue(old.getResultsJson(), new TypeReference<>() { }); }
        catch (Exception e) { throw new BizException("任务结果损坏，请重新生成方案"); }
        if (results == null || results.stream().filter(result -> !result.success()).count() != 1)
            throw new BizException("无法确定唯一失败步骤，请重新生成方案");
        int index = results.stream().filter(result -> !result.success()).findFirst().orElseThrow().index();
        if (index < 0 || index >= original.size()) throw new BizException("任务结果与步骤不匹配");
        Automation.Step step = original.get(index);
        files.verify(step);
        switch (step.action()) {
            case "MOVE_FILE" -> files.checkTarget(step.storageKey(), step.targetPath());
            case "RENAME_FILE" -> files.checkRenameTarget(step.storageKey(), step.targetPath());
            case "INDEX_MATERIAL" -> {
                boolean indexed = learning.materials(step.courseId()).stream()
                        .anyMatch(item -> Objects.equals(item.getStorageKey(), step.storageKey())
                                && Objects.equals(item.getPath(), step.sourcePath()));
                if (indexed) throw new BizException("资料已归档，请刷新课程资料后确认");
            }
            default -> throw new BizException("不支持重试此类任务");
        }
        if (tasks.selectCount(owned(current).eq(AgentTask::getStatus, "PENDING").gt(AgentTask::getExpiresAt, System.currentTimeMillis())) >= 20)
            throw new BizException("待确认任务过多，请先取消旧任务");
        AgentTask retry = new AgentTask();
        retry.setId(UUID.randomUUID().toString());
        retry.setOwnerUserId(current);
        retry.setTitle("重试失败步骤：" + old.getTitle());
        retry.setStatus("PENDING");
        retry.setStepsJson(encode(List.of(step)));
        retry.setResultsJson("[]");
        retry.setCreatedAt(System.currentTimeMillis());
        retry.setExpiresAt(retry.getCreatedAt() + 600_000);
        tasks.insert(retry);
        return view(retry);
    }

    public Automation.View cancel(String id) {
        int owner = owner();
        require(id, owner);
        tasks.update(null, new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getId, id).eq(AgentTask::getOwnerUserId, owner)
                .eq(AgentTask::getStatus, "PENDING").set(AgentTask::getStatus, "CANCELLED"));
        return view(require(id, owner));
    }

    /** Only the confirmation endpoint calls this method; never exposed as a model tool. */
    public Automation.View execute(String id, boolean confirmed) {
        int owner = owner();
        if (!confirmed) throw new BizException("请先确认任务");
        AgentTask task = require(id, owner);
        if (!task.getStatus().equals("PENDING")) return view(task); // Idempotent retry, never repeat filesystem work.
        if (task.getExpiresAt() <= System.currentTimeMillis()) throw new BizException("任务已过期，请重新生成并确认");
        List<Automation.Step> steps = decodeSteps(task); // Validate persisted data before claiming the task.
        executor.reserve();
        boolean submitted = false;
        try {
            int claimed = tasks.update(null, new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getId, id)
                .eq(AgentTask::getOwnerUserId, owner).eq(AgentTask::getStatus, "PENDING")
                .gt(AgentTask::getExpiresAt, System.currentTimeMillis()).set(AgentTask::getStatus, "RUNNING"));
            if (claimed != 1) return view(require(id, owner));
            try {
                executor.submit(() -> BackgroundActorContext.runAs(owner, () -> run(id, owner, steps)));
                submitted = true;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                tasks.update(null, new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getId, id)
                        .eq(AgentTask::getOwnerUserId, owner).eq(AgentTask::getStatus, "RUNNING")
                        .set(AgentTask::getStatus, "PENDING"));
                throw new BizException("后台任务暂不可用，任务仍待确认，请稍后重试");
            }
            return view(require(id, owner));
        } finally {
            if (!submitted) executor.release();
        }
    }

    /** Runs without the HTTP request/session. The scope contains only the server-verified owner ID. */
    private void run(String id, int owner, List<Automation.Step> steps) {
        List<Automation.StepResult> results = new ArrayList<>();
        boolean failed = false;
        try {
        for (int index = 0; index < steps.size(); index++) {
            try {
                if (Thread.currentThread().isInterrupted()) throw new BizException("服务正在关闭，已停止后续步骤");
                users.requireLoginUser(owner); // Disabled accounts / workspaces must not continue with an old confirmation.
                if (!"RUNNING".equals(require(id, owner).getStatus())) throw new BizException("任务状态已变化，已停止后续步骤");
                var step = steps.get(index);
                files.verify(step);
                switch (step.action()) {
                    case "MOVE_FILE" -> {
                        var plan = learning.preparePathChange(step.storageKey(), step.sourcePath(), step.targetPath(), false);
                        files.move(step);
                        learning.completePathChange(plan);
                    }
                    case "RENAME_FILE" -> {
                        var plan = learning.preparePathChange(step.storageKey(), step.sourcePath(), step.targetPath(), false);
                        files.rename(step);
                        learning.completePathChange(plan);
                    }
                    case "INDEX_MATERIAL" -> learning.add(new LearningRequests.Material(step.courseId(), step.storageKey(), step.sourcePath(), step.tags()));
                    default -> throw new BizException("不支持的任务步骤");
                }
                results.add(new Automation.StepResult(index, true, "已完成"));
            } catch (LearningPathSyncException e) {
                results.add(new Automation.StepResult(index, false, e.getMessage()));
                persist(id, "INTERRUPTED", results);
                return; // The physical write succeeded; never offer the ordinary failed-step retry.
            } catch (Exception e) {
                results.add(new Automation.StepResult(index, false, e instanceof BizException ? e.getMessage() : "执行失败，请检查权限与存储状态"));
                failed = true;
            }
            // Independent DB updates deliberately do not wrap filesystem writes in a misleading transaction.
            persist(id, "RUNNING", results);
            if (failed) break;
        }
        persist(id, failed ? (results.stream().anyMatch(Automation.StepResult::success) ? "PARTIAL" : "FAILED") : "SUCCEEDED", results);
        } catch (Exception e) {
            // A DB/checkpoint error may happen after a filesystem write; never blindly retry it.
            log.error("Agent task {} interrupted; manual verification is required", id, e);
            try { persist(id, "INTERRUPTED", results); }
            catch (Exception checkpointFailure) { log.error("Unable to checkpoint interrupted agent task {}", id, checkpointFailure); }
        }
    }

    /** Single-instance startup recovery only: mark uncertain tasks, never replay file operations. */
    @Override
    public void afterSingletonsInstantiated() {
        int count = tasks.update(null, new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getStatus, "RUNNING")
                .set(AgentTask::getStatus, "INTERRUPTED"));
        if (count > 0) log.warn("Marked {} interrupted Agent tasks for manual verification; no steps were replayed", count);
    }

    private int owner() {
        Integer id = TlgxyCloudAuthUtil.getCurrentUserId();
        users.requireLoginUser(id);
        return id;
    }
    private LambdaQueryWrapper<AgentTask> owned(int owner) { return new LambdaQueryWrapper<AgentTask>().eq(AgentTask::getOwnerUserId, owner); }
    private AgentTask require(String id, int owner) {
        var task = tasks.selectOne(owned(owner).eq(AgentTask::getId, id));
        if (task == null) throw new BizException("任务不存在或无权访问");
        return task;
    }
    private void persist(String id, String status, List<Automation.StepResult> results) {
        tasks.update(null, new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getId, id)
                .set(AgentTask::getStatus, status).set(AgentTask::getResultsJson, encode(results)));
    }
    private String encode(Object data) {
        try { return json.writeValueAsString(data); } catch (Exception e) { throw new IllegalStateException("任务编码失败", e); }
    }
    private List<Automation.Step> decodeSteps(AgentTask task) {
        try {
            List<Automation.Step> steps = json.readValue(task.getStepsJson(), new TypeReference<>() { });
            if (steps == null || steps.isEmpty() || steps.size() > 20 || steps.contains(null)) throw new IllegalArgumentException();
            return steps;
        }
        catch (Exception e) { throw new BizException("任务数据损坏，请重新生成"); }
    }
    private Automation.View view(AgentTask task) {
        try {
            String status = task.getStatus().equals("PENDING") && task.getExpiresAt() <= System.currentTimeMillis() ? "EXPIRED" : task.getStatus();
            return new Automation.View(task.getId(), task.getTitle(), status, decodeSteps(task),
                    json.readValue(task.getResultsJson(), new TypeReference<>() { }), task.getCreatedAt(), task.getExpiresAt(), NOTICE);
        } catch (Exception e) { throw new BizException("任务记录读取失败"); }
    }
    static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    static String datedName(String name, long modifiedAt, int sequence) {
        String ext = extension(name);
        return FILE_DATE.format(Instant.ofEpochMilli(modifiedAt)) + "-" + String.format(Locale.ROOT, "%02d", sequence)
                + (ext.isEmpty() ? "" : "." + ext);
    }
    static String category(String name) {
        String ext = extension(name);
        if (Set.of("txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx").contains(ext)) return "文档";
        if (Set.of("jpg", "jpeg", "png", "gif", "webp", "svg").contains(ext)) return "图片";
        if (Set.of("mp4", "mkv", "avi", "mov", "webm").contains(ext)) return "视频";
        if (Set.of("mp3", "wav", "flac", "aac").contains(ext)) return "音频";
        if (Set.of("zip", "rar", "7z", "gz", "tar").contains(ext)) return "压缩包";
        if (Set.of("java", "js", "ts", "py", "c", "cpp", "vue", "html", "css", "sql", "json").contains(ext)) return "代码";
        return "其他";
    }
    @org.springframework.context.event.EventListener
    public void onUserDeleted(im.tlgxy.cloud.module.user.event.UserDeleteEvent event) { tasks.delete(owned(event.getId())); }
}

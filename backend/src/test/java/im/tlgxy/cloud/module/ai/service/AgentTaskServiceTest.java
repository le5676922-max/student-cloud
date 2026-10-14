package im.tlgxy.cloud.module.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.core.util.BackgroundActorContext;
import im.tlgxy.cloud.module.ai.mapper.AgentTaskMapper;
import im.tlgxy.cloud.module.ai.model.automation.AgentTask;
import im.tlgxy.cloud.module.ai.model.automation.Automation;
import im.tlgxy.cloud.module.learning.service.LearningLibraryService;
import im.tlgxy.cloud.module.user.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskServiceTest {
    private final AgentTaskMapper tasks = mock(AgentTaskMapper.class);
    private final AutomationFileService files = mock(AutomationFileService.class);
    private final LearningLibraryService learning = mock(LearningLibraryService.class);
    private final UserService users = mock(UserService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentTaskExecutor executor = mock(AgentTaskExecutor.class);
    private final AgentTaskService service = new AgentTaskService(tasks, files, learning, users, json, executor);
    private final List<LambdaUpdateWrapper<AgentTask>> updates = new ArrayList<>();
    private MockedStatic<TlgxyCloudAuthUtil> auth;
    private AgentTask task;
    private List<Automation.Step> steps;

    @BeforeEach void setup() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "task-test"), AgentTask.class);
        auth = mockStatic(TlgxyCloudAuthUtil.class);
        auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(7);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; }).when(executor).submit(any());
        steps = IntStream.range(0, 3).mapToObj(i -> new Automation.Step("MOVE_FILE", "local", "/" + i + ".txt",
                "/文档/" + i + ".txt", null, "", 10, 100, "identity")).toList();
        task = new AgentTask();
        task.setId("task-id"); task.setOwnerUserId(7); task.setTitle("test"); task.setStatus("PENDING");
        task.setCreatedAt(System.currentTimeMillis()); task.setExpiresAt(System.currentTimeMillis() + 600000);
        task.setStepsJson(json.writeValueAsString(steps)); task.setResultsJson("[]");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        when(tasks.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<AgentTask> update = invocation.getArgument(1);
            updates.add(update);
            // Simulate the persisted SET values; HTTP integration tests cover the actual SQL CAS.
            var matcher = java.util.regex.Pattern.compile("(status|results_json)=#\\{ew.paramNameValuePairs.([^}]+)}").matcher(update.getSqlSet());
            while (matcher.find()) {
                String value = update.getParamNameValuePairs().get(matcher.group(2)).toString();
                if (matcher.group(1).equals("status")) task.setStatus(value); else task.setResultsJson(value);
            }
            return 1;
        });
    }
    @AfterEach void close() { auth.close(); }

    @Test void scheduledOccurrenceReusesExistingTaskButStillChecksCurrentSourcePermissions() {
        String occurrence = "bc4e1a98-265f-4e36-84d4-6c8bbf53cb9c";
        task.setId(occurrence);
        var request = new Automation.Request("ORGANIZE_FILES", "local", "/", null, "");
        assertEquals(occurrence, service.previewForSchedule(request, occurrence).id());
        verify(files).list("local", "/", true);
        verify(tasks, never()).insert(any(AgentTask.class));
        when(files.list("local", "/", true)).thenThrow(new BizException("permission revoked"));
        assertThrows(BizException.class, () -> service.previewForSchedule(request, occurrence));
        verify(tasks, never()).insert(any(AgentTask.class));
    }

    @Test void repeatedScheduledInsertConflictReturnsOnlyOwnedPersistedOccurrence() {
        String occurrence = "bc4e1a98-265f-4e36-84d4-6c8bbf53cb9c";
        task.setId(occurrence);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null, task);
        when(files.list("local", "/", true)).thenReturn(List.of(new AutomationFileService.Entry("/a.txt", 10, 100, "token")));
        when(tasks.insert(any(AgentTask.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("race"));
        var result = service.previewForSchedule(new Automation.Request("ORGANIZE_FILES", "local", "/", null, ""), occurrence);
        assertEquals(occurrence, result.id());
        verify(tasks, times(1)).insert(any(AgentTask.class));
        verify(executor, never()).submit(any());
    }

    @Test void partialFailureStopsRemainingStepsAndDoesNotReplaySuccessfulWrites() {
        doThrow(new BizException("源文件已变化")).when(files).verify(steps.get(1));
        var result = service.execute(task.getId(), true);
        assertEquals("PARTIAL", result.status());
        assertEquals(List.of(true, false), result.results().stream().map(Automation.StepResult::success).toList());
        verify(files).move(steps.getFirst());
        verify(files, never()).move(steps.get(1));
        verify(files, never()).verify(steps.get(2));
        assertEquals(result, service.execute(task.getId(), true));
        verify(files, times(1)).move(any());
        assertEquals(4, updates.size()); // CAS + two step checkpoints + final status
    }

    @Test void permissionRevalidationFailureBeforeSecondStepProducesPartialNotSuccess() {
        when(users.requireLoginUser(7)).thenReturn(null, null).thenThrow(new BizException("账号已停用"));
        var result = service.execute(task.getId(), true);
        assertEquals("PARTIAL", result.status());
        assertEquals("账号已停用", result.results().get(1).message());
        verify(files, times(1)).move(any());
    }

    @Test void unexpectedFilesystemDetailsAreNotExposedToTheClient() {
        doThrow(new IllegalStateException("private server path and credentials")).when(files).move(steps.getFirst());
        var result = service.execute(task.getId(), true);
        assertEquals("FAILED", result.status());
        assertFalse(result.results().toString().contains("credentials"));
        verify(files, never()).verify(steps.get(1));
    }

    @Test void expiredUnconfirmedAndCorruptedTasksNeverAcquireExecution() {
        assertThrows(BizException.class, () -> service.execute(task.getId(), false));
        task.setExpiresAt(1L);
        assertThrows(BizException.class, () -> service.execute(task.getId(), true));
        task.setExpiresAt(System.currentTimeMillis() + 600000);
        for (String invalid : List.of("broken", "null", "[]", "[null]")) {
            task.setStepsJson(invalid);
            assertThrows(BizException.class, () -> service.execute(task.getId(), true));
        }
        assertTrue(updates.isEmpty());
        verifyNoInteractions(files, learning);
    }

    @Test void losingCompareAndSetDoesNotExecuteAnyStep() {
        when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(0);
        service.execute(task.getId(), true);
        verifyNoInteractions(files, learning);
        verify(executor).release();
    }

    @Test void confirmationReturnsBeforeWorkAndRepeatedConfirmationNeverEnqueuesTwice() {
        var pending = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        doAnswer(invocation -> { pending.set(invocation.getArgument(0)); return null; }).when(executor).submit(any());
        assertEquals("RUNNING", service.execute(task.getId(), true).status());
        verifyNoInteractions(files, learning);
        assertEquals("RUNNING", service.execute(task.getId(), true).status());
        verify(executor, times(1)).submit(any());
        doAnswer(invocation -> {
            assertEquals(7, BackgroundActorContext.currentActor());
            return null;
        }).when(files).move(any());
        pending.get().run();
        assertNull(BackgroundActorContext.currentActor());
        assertEquals("SUCCEEDED", task.getStatus());
        verify(files, times(3)).move(any());
    }

    @Test void fullQueueRejectsBeforeClaimingAndSubmissionRejectionRestoresPending() {
        doThrow(new BizException("繁忙")).when(executor).reserve();
        assertThrows(BizException.class, () -> service.execute(task.getId(), true));
        assertEquals("PENDING", task.getStatus());
        assertTrue(updates.isEmpty());
        doNothing().when(executor).reserve();
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(executor).submit(any());
        assertThrows(BizException.class, () -> service.execute(task.getId(), true));
        assertEquals("PENDING", task.getStatus());
        verify(executor).release();
        verifyNoInteractions(files, learning);
    }

    @Test void revocationWhileQueuedStopsFirstStepAndClearsIdentity() {
        var pending = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        doAnswer(invocation -> { pending.set(invocation.getArgument(0)); return null; }).when(executor).submit(any());
        service.execute(task.getId(), true);
        when(users.requireLoginUser(7)).thenThrow(new BizException("账号已停用"));
        pending.get().run();
        assertEquals("FAILED", task.getStatus());
        assertNull(BackgroundActorContext.currentActor());
        verifyNoInteractions(files, learning);
    }

    @Test void previewIsBoundedAndDoesNotWriteFiles() {
        when(tasks.selectCount(any())).thenReturn(0L);
        when(files.list("local", "/", true)).thenReturn(IntStream.range(0, 25)
                .mapToObj(i -> new AutomationFileService.Entry("/" + i + ".TXT", 10, 100, "id")).toList());
        var plan = service.preview(new Automation.Request("ORGANIZE_FILES", "local", "/", null, ""));
        assertEquals("PENDING", plan.status());
        assertEquals(20, plan.steps().size());
        assertTrue(plan.steps().stream().allMatch(s -> s.targetPath().startsWith("/文档/")));
        verify(files, never()).move(any());
        verifyNoInteractions(learning);
        verify(tasks).insert(any(AgentTask.class));
    }

    @Test void tooManyPendingTasksRejectsBeforeScanningOrPersisting() {
        when(tasks.selectCount(any())).thenReturn(20L);
        assertThrows(BizException.class, () -> service.preview(new Automation.Request("ORGANIZE_FILES", "local", "/", null, "")));
        verifyNoInteractions(files, learning);
        verify(tasks, never()).insert(any(AgentTask.class));
    }

    @Test void importPreviewSkipsUnsupportedOversizedAndPreviouslyIndexedFiles() {
        when(tasks.selectCount(any())).thenReturn(0L);
        var indexed = new im.tlgxy.cloud.module.learning.model.LearningMaterial();
        indexed.setStorageKey("local"); indexed.setPath("/old.md");
        when(learning.materials(1)).thenReturn(List.of(indexed));
        when(files.list("local", "/", false)).thenReturn(List.of(
                new AutomationFileService.Entry("/old.md", 10, 1, "id"),
                new AutomationFileService.Entry("/picture.png", 10, 1, "id"),
                new AutomationFileService.Entry("/too-big.pdf", 5 * 1024 * 1024 + 1, 1, "id"),
                new AutomationFileService.Entry("/new.docx", 10, 1, "id")));
        var plan = service.preview(new Automation.Request("IMPORT_LEARNING", "local", "/", 1, "课程"));
        assertEquals(List.of("/new.docx"), plan.steps().stream().map(Automation.Step::sourcePath).toList());
        verify(learning, never()).add(any());
        verify(files, never()).move(any());
    }

    @Test void dateRenameRequiresASeparatePreviewAndCallsOnlyRename() {
        when(files.listForRename("local", "/")).thenReturn(List.of(
                new AutomationFileService.Entry("/report.PDF", 10, 0, "id")));
        var plan = service.preview(new Automation.Request("RENAME_BY_DATE", "local", "/", null, ""));
        assertEquals("RENAME_FILE", plan.steps().getFirst().action());
        assertEquals("/19700101-080000-01.pdf", plan.steps().getFirst().targetPath());
        verify(files, never()).rename(any());
        task.setStepsJson(jsonValue(plan.steps()));
        service.execute(task.getId(), true);
        verify(files).rename(plan.steps().getFirst());
        verify(files, never()).move(any());
    }

    @Test void retryCreatesOnlyTheExplicitlyFailedStep() {
        task.setStatus("PARTIAL");
        task.setResultsJson(jsonValue(List.of(new Automation.StepResult(0, true, "已完成"),
                new Automation.StepResult(1, false, "暂时失败"))));
        var retry = service.retryFailed(task.getId());
        assertEquals("PENDING", retry.status());
        assertEquals(List.of(steps.get(1)), retry.steps());
        verify(files).verify(steps.get(1));
        verify(files).checkTarget("local", steps.get(1).targetPath());
        verify(files, never()).move(any());
    }

    private String jsonValue(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}

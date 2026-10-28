package io.studentcloud.intelligence.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.tasks.TaskLease;
import io.studentcloud.tasks.TaskService;
import io.studentcloud.tasks.TaskType;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable local consumer. The content service fences and deduplicates every filesystem command. */
@Component
public final class AgentWorker {
    private final TaskService tasks;
    private final AccountDirectory accounts;
    private final AgentContentClient content;
    private final ObjectMapper json = new ObjectMapper();
    private final String workerId = "intelligence-" + UUID.randomUUID();

    public AgentWorker(TaskService tasks, AccountDirectory accounts, AgentContentClient content) {
        this.tasks=tasks; this.accounts=accounts; this.content=content;
    }

    @Scheduled(initialDelay=2000, fixedDelayString="${student-cloud.agent.poll-ms:2000}")
    public void poll() {
        for (int i=0; i<4; i++) {
            var claim = tasks.claim(workerId, 180);
            if (claim.isEmpty()) return;
            TaskLease lease = claim.get();
            tasks.acknowledgeClaimedTask(lease.id());
            execute(lease);
        }
    }

    private void execute(TaskLease lease) {
        try {
            if (lease.task().type() != TaskType.FILE_ORGANIZE) {
                tasks.fail(lease, "UNSUPPORTED_TASK_TYPE", false, Duration.ZERO); return;
            }
            var actor = accounts.account(lease.task().actorId());
            Long expectedWorkspace = lease.task().workspaceId() == 0 ? null : lease.task().workspaceId();
            if (!Objects.equals(actor.workspaceId(), expectedWorkspace)) {
                tasks.fail(lease, "WORKSPACE_CHANGED", false, Duration.ZERO); return;
            }
            Map<?,?> payload = json.readValue(lease.task().payload(), Map.class);
            String token = (String) payload.get("previewToken");
            if (token == null || token.isBlank()) {
                tasks.fail(lease, "INVALID_PLAN", false, Duration.ZERO); return;
            }
            AgentContentClient.Execution result = content.execute(actor.id(), actor.workspaceId(), token, lease.id());
            String status = result.status();
            if (!java.util.List.of("COMPLETED", "PARTIAL", "FAILED", "INDETERMINATE").contains(status)) {
                tasks.fail(lease, "TOOL_RESULT_UNCERTAIN", false, Duration.ZERO); return;
            }
            tasks.finish(lease, json.writeValueAsString(result), "COMPLETED".equals(status));
        } catch (ApplicationFault failure) {
            boolean retry = "CONTENT_UNAVAILABLE".equals(failure.code());
            tasks.fail(lease, retry ? "CONTENT_UNAVAILABLE" : "AUTHORIZATION_CHANGED", retry, Duration.ofSeconds(5));
        } catch (IOException | ClassCastException failure) {
            tasks.fail(lease, "INVALID_PLAN", false, Duration.ZERO);
        }
    }
}

package io.studentcloud.intelligence.schedule;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A separate scheduler thread lets weekly previews coexist with confirmed file jobs. */
@Component
public final class AgentScheduleRunner {
    private final AgentScheduleService schedules;
    private final io.studentcloud.intelligence.agent.AgentPlanService plans;
    public AgentScheduleRunner(AgentScheduleService schedules,io.studentcloud.intelligence.agent.AgentPlanService plans){this.schedules=schedules;this.plans=plans;}
    @Scheduled(initialDelayString="${student-cloud.agent.schedule-start-ms:15000}",fixedDelayString="${student-cloud.agent.schedule-poll-ms:30000}")
    public void tick(){
        plans.cleanupExpiredPreviews();
        schedules.expireStaleClaims(300_000);
        schedules.runDue();
    }
}

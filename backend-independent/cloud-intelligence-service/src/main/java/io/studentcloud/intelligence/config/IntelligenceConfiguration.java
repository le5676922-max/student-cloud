package io.studentcloud.intelligence.config;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.assistant.AnswerFeedbackService;
import io.studentcloud.assistant.CourseKnowledgeAccess;
import io.studentcloud.assistant.MemoryService;
import io.studentcloud.assistant.ReviewedKnowledgeService;
import io.studentcloud.tasks.TaskService;
import io.studentcloud.intelligence.ai.ChatAnswerStore;
import io.studentcloud.intelligence.ai.OpenAiChatClient;
import io.studentcloud.intelligence.ai.ChatService;
import io.studentcloud.intelligence.ai.ProjectKnowledgeService;
import io.studentcloud.intelligence.agent.AgentPlanService;
import io.studentcloud.intelligence.agent.AgentContentClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
public class IntelligenceConfiguration {
    @Bean @DependsOn("intelligenceSchema")
    MemoryService memories(JdbcTemplate jdbc, PlatformTransactionManager tx, Clock clock, AccountDirectory accounts) {
        return new MemoryService(jdbc, tx, clock, accounts);
    }
    @Bean @DependsOn("intelligenceSchema")
    AnswerFeedbackService feedback(JdbcTemplate jdbc, PlatformTransactionManager tx, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses) {
        return new AnswerFeedbackService(jdbc, tx, clock, accounts, courses);
    }
    @Bean @DependsOn("intelligenceSchema")
    ReviewedKnowledgeService knowledge(JdbcTemplate jdbc, PlatformTransactionManager tx, Clock clock, AccountDirectory accounts, CourseKnowledgeAccess courses,ChatAnswerStore answers) {
        return new ReviewedKnowledgeService(jdbc, tx, clock, accounts, courses,answers::requirePublishable);
    }
    @Bean @DependsOn("intelligenceSchema")
    TaskService tasks(JdbcTemplate jdbc, PlatformTransactionManager tx, Clock clock) { return new TaskService(jdbc, tx, clock); }
    @Bean @DependsOn("intelligenceSchema")
    ChatAnswerStore chatAnswers(JdbcTemplate jdbc, Clock clock) { return new ChatAnswerStore(jdbc, clock); }
    @Bean
    OpenAiChatClient chatModel(@Value("${student-cloud.ai.base-url}") String base,
            @Value("${student-cloud.ai.model}") String model, @Value("${student-cloud.ai.key:}") String key) {
        return new OpenAiChatClient(base, model, key);
    }
    @Bean
    ProjectKnowledgeService projectKnowledge() { return new ProjectKnowledgeService(); }
    @Bean
    ChatService chat(AccountDirectory accounts, MemoryService memories, ReviewedKnowledgeService knowledge,
            OpenAiChatClient model, ChatAnswerStore answers,io.studentcloud.intelligence.ai.LearningKnowledgeClient library,AgentPlanService plans,
            ProjectKnowledgeService project) {
        return new ChatService(accounts, memories, knowledge, model, answers,library,plans,project);
    }
    @Bean @DependsOn("intelligenceSchema")
    AgentPlanService agentPlans(JdbcTemplate jdbc, PlatformTransactionManager tx, Clock clock,
            AccountDirectory accounts, AgentContentClient content, TaskService tasks) {
        return new AgentPlanService(jdbc, tx, clock, accounts, content, tasks);
    }
    @Bean
    io.studentcloud.intelligence.schedule.AgentScheduleService.PreviewPort schedulePreviews(AgentPlanService plans){
        return plans::previewBulk;
    }
}

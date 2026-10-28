package io.studentcloud.content.http;
import io.studentcloud.content.application.AgentCommandService;
import io.studentcloud.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/internal/content/agent")
public class InternalAgentController {
    private final AgentCommandService commands;
    public InternalAgentController(AgentCommandService commands){this.commands=commands;}
    public record PreviewInput(long actorId,Long workspaceId,String storageKey,List<AgentCommandService.Step> steps,String type,String path,Long courseId,String tags){}
    public record ExecuteInput(long actorId,Long workspaceId,String previewToken,String idempotencyKey){}
    @PostMapping("/preview") public ApiResponse<AgentCommandService.Preview> preview(@RequestBody PreviewInput input){return ApiResponse.success(input.steps()==null?commands.previewBulk(input.actorId(),input.workspaceId(),input.storageKey(),input.type(),input.path(),input.courseId(),input.tags()):commands.preview(input.actorId(),input.workspaceId(),input.storageKey(),input.steps()));}
    @PostMapping("/execute") public ApiResponse<AgentCommandService.Execution> execute(@RequestBody ExecuteInput input){return ApiResponse.success(commands.execute(input.actorId(),input.workspaceId(),input.previewToken(),input.idempotencyKey()));}
    public record DuplicateInput(long actorId,Long workspaceId,String storageKey,String path){}
    @PostMapping("/duplicates") public ApiResponse<AgentCommandService.DuplicateReport> duplicates(@RequestBody DuplicateInput input){return ApiResponse.success(commands.duplicates(input.actorId(),input.workspaceId(),input.storageKey(),input.path()));}
}

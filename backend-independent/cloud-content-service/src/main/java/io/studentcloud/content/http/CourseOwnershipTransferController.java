package io.studentcloud.content.http;

import io.studentcloud.course.CourseOwnershipTransferService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="student-cloud.ownership-transfer.enabled",havingValue="true")
@RequestMapping("/api/courses/{courseId}/ownership-transfers")
public class CourseOwnershipTransferController {
    private final CourseOwnershipTransferService transfers;
    public CourseOwnershipTransferController(CourseOwnershipTransferService transfers){this.transfers=transfers;}
    @ModelAttribute void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    public record Create(@Positive long toAccountId,@PositiveOrZero long expectedRevision,long expiresAt,@NotBlank @Size(max=100) String idempotencyKey){}
    @GetMapping public ApiResponse<CourseOwnershipTransferService.Snapshot> snapshot(@PathVariable long courseId,HttpServletRequest request){return ApiResponse.success(transfers.snapshot(AuthenticationFilter.actor(request),courseId));}
    @PostMapping public ApiResponse<CourseOwnershipTransferService.Transfer> create(@PathVariable long courseId,@Valid @RequestBody Create input,HttpServletRequest request){return ApiResponse.success(transfers.create(AuthenticationFilter.actor(request),courseId,input.toAccountId(),input.expectedRevision(),input.expiresAt(),input.idempotencyKey()));}
    @PostMapping("/{id}/accept") public ApiResponse<CourseOwnershipTransferService.Transfer> accept(@PathVariable long courseId,@PathVariable String id,HttpServletRequest request){return decide(request,courseId,id,"accept");}
    @PostMapping("/{id}/reject") public ApiResponse<CourseOwnershipTransferService.Transfer> reject(@PathVariable long courseId,@PathVariable String id,HttpServletRequest request){return decide(request,courseId,id,"reject");}
    @PostMapping("/{id}/cancel") public ApiResponse<CourseOwnershipTransferService.Transfer> cancel(@PathVariable long courseId,@PathVariable String id,HttpServletRequest request){return decide(request,courseId,id,"cancel");}
    private ApiResponse<CourseOwnershipTransferService.Transfer> decide(HttpServletRequest request,long courseId,String id,String action){return ApiResponse.success(transfers.decide(AuthenticationFilter.actor(request),courseId,id,action));}
}

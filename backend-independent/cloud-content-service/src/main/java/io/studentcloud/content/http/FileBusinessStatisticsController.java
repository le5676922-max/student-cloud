package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.content.statistics.FileBusinessStatistics;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class FileBusinessStatisticsController {
    private final FileBusinessStatistics statistics;
    public FileBusinessStatisticsController(FileBusinessStatistics statistics){this.statistics=statistics;}
    @GetMapping("/admin/file-business/overview")
    public ApiResponse<FileBusinessStatistics.Overview> overview(@RequestParam(defaultValue="7") int days,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.success(statistics.overview(AuthenticationFilter.actor(request),days));
    }
}

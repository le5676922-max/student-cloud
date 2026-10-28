package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Internal service-key filter protects this aggregate; identity and member data never cross database boundaries. */
@RestController
public class InternalWorkspaceSummaryController {
    private final JdbcTemplate jdbc;
    public InternalWorkspaceSummaryController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/internal/content/workspaces/{id}/storage-count") public ApiResponse<Long> count(@PathVariable long id){
        if(id<1) throw new ApplicationFault("41000",400,"空间标识无效");
        return ApiResponse.success(jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount WHERE workspace_id=?",Long.class,id));
    }
}

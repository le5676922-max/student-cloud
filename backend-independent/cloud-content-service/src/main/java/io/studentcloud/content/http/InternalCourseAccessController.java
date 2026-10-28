package io.studentcloud.content.http;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.course.SharedCourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal credential guard is installed by ServiceRuntimeConfiguration before this endpoint. */
@RestController
public class InternalCourseAccessController {
    private final SharedCourseService courses;
    private final AccountDirectory accounts;
    public InternalCourseAccessController(SharedCourseService courses, AccountDirectory accounts) {
        this.courses = courses; this.accounts = accounts;
    }

    @GetMapping("/internal/content/courses/{id}/read")
    public ApiResponse<Void> read(@PathVariable long id, @RequestParam long accountId) {
        if (id < 1 || accountId < 1) throw new ApplicationFault("41000", 400, "课程或账号标识无效");
        courses.requireRead(accounts.account(accountId), id);
        return ApiResponse.success(null);
    }
}

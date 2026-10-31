package io.studentcloud.runtime.http;

import io.studentcloud.common.ApplicationFault;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为尚未实现的接口明确返回不支持，避免伪成功或返回HTML。
 *
 * Pending endpoints must never report success or fall back to an HTML page.
 */
@RestController
public class UnimplementedApiController {
    @RequestMapping({"/api/**", "/user/**", "/admin/**"})
    public Object pending() {
        throw new ApplicationFault("NOT_IMPLEMENTED", 501, "独立后端尚未实现此接口，当前开发版本不能替换正式网站");
    }
}

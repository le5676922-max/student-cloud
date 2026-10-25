package im.tlgxy.cloud.module.support;

import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SupportSocketTicketController {
    private final SupportSocketAuth auth;

    @PostMapping("/api/support/socket-ticket")
    @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<SupportSocketAuth.Ticket> ticket() {
        return AjaxJson.getSuccessData(auth.issue(StpUtil.getLoginIdAsInt(), StpUtil.getTokenValue()));
    }
}

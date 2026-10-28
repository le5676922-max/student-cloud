package io.studentcloud.platform.http;

import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.RequestTokens;

import io.studentcloud.account.AccountService;
import io.studentcloud.account.PlatformAdministrationService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Honest bootstrap configuration for the development slice; absent features remain disabled. */
@RestController
public class BootstrapCompatibilityController {
    private final AccountService accounts;
    private final PlatformAdministrationService administration;
    public BootstrapCompatibilityController(AccountService accounts,PlatformAdministrationService administration) { this.accounts = accounts;this.administration=administration; }

    @GetMapping("/api/site/config/global")
    public ApiResponse<Map<String, Object>> site() {
        var config = new LinkedHashMap<String, Object>();
        config.put("siteName", accounts.siteName());
        config.put("installed",accounts.installationStatus());
        config.put("accountModel","independent");
        config.put("siteHomeName", "首页");
        config.put("showLogin", true);
        config.put("rootShowStorage", true);
        config.put("tableSize", "small");
        config.put("layout", "full");
        config.put("mobileLayout", "full");
        config.put("debugMode", false);
        config.put("searchEnable", false);
        config.put("showDocument", false);
        config.put("showLinkBtn", false);
        config.put("showShortLink", false);
        config.put("showPathLink", false);
        config.put("fileClickMode", "dbclick");
        config.put("mobileFileClickMode", "dbclick");
        config.put("defaultSortField", "name");
        config.put("defaultSortOrder", "asc");
        config.put("enableNormalDownloadConfirm", true);
        config.put("version", "0.1.0-SNAPSHOT");
        config.put("maxShowSize",1000);config.put("loadMoreSize",50);
        config.put("directLinkPrefix","");config.put("linkExpireTimes","");config.put("forceBackendAddress","");
        config.put("infoEnable",false);config.put("recordDownloadLog",false);config.put("showAnnouncement",false);
        config.put("icp","");config.put("announcement","");config.put("siteHomeLogo","");
        config.put("siteHomeLogoLink","");config.put("siteHomeLogoTargetMode","");
        config.putAll(administration.publicSiteView());
        return ApiResponse.success(config);
    }

    @GetMapping("/api/sso/list")
    public ApiResponse<List<Object>> sso() { return ApiResponse.success(List.of()); }

    @GetMapping("/user/login/verify-mode")
    public ApiResponse<String> verificationMode() { return ApiResponse.success("off"); }

    @GetMapping("/user/login/entry/validate")
    public ApiResponse<Boolean> loginEntry(@RequestParam(required=false) String entry) {
        if (entry != null && !entry.isBlank()) throw new ApplicationFault("41000", 400, "当前开发版本未启用自定义登录入口");
        return ApiResponse.success(true);
    }

}

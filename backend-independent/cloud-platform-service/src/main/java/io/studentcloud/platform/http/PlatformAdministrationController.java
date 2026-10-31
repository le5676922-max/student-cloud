package io.studentcloud.platform.http;

import io.studentcloud.account.PlatformAdministrationService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供全局管理员的空间、管理员账号和站点配置管理接口。 Independent global-admin adapters; all mutation and directory access uses fresh account authorization. */
@RestController
public final class PlatformAdministrationController {
    private final PlatformAdministrationService administration;
    public PlatformAdministrationController(PlatformAdministrationService administration) { this.administration=administration; }

    public record TenantRequest(Long id,@NotBlank @Size(max=80) String name,boolean enable) { }
    public record WorkspaceAdministratorRequest(Long id,@NotBlank @Size(min=3,max=64) String username,
            @Email @Size(max=254) String email,@Size(max=128) String password,
            String oldPassword,@Size(max=100) String nickname,Long tenantId,@NotBlank String role,Boolean enable) { }

    /** 返回平台身份侧概况与运行状态。 */
    @GetMapping("/admin/overview")
    public ApiResponse<Map<String,Object>> overview(HttpServletRequest request) {
        return ApiResponse.success(administration.overview(AuthenticationFilter.actor(request)));
    }

    /** 按条件分页返回空间及其身份侧统计。 */
    @GetMapping("/admin/overview/workspaces")
    public ApiResponse<PlatformAdministrationService.Page<PlatformAdministrationService.Workspace>> workspaces(
            @RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Boolean enabled,
            @RequestParam(required=false) Long workspaceId,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int pageSize,HttpServletRequest request) {
        return ApiResponse.success(administration.workspaces(AuthenticationFilter.actor(request),keyword,enabled,workspaceId,page,pageSize));
    }

    /** 按指定空间、角色和搜索条件分页返回账号。 */
    @GetMapping("/admin/overview/workspaces/{id}/users")
    public ApiResponse<PlatformAdministrationService.Page<PlatformAdministrationService.Member>> users(
            @PathVariable long id,@RequestParam(defaultValue="TENANT_ADMIN") String role,@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest request) {
        return ApiResponse.success(administration.workspaceUsers(AuthenticationFilter.actor(request),id,role,keyword,page,pageSize));
    }

    /** 将平台空间目录转换为兼容管理前端的字段。 */
    @GetMapping("/admin/tenants")
    public ApiResponse<List<Map<String,Object>>> tenants(HttpServletRequest request) {
        List<Map<String,Object>> records=new ArrayList<>();
        for(var row:administration.tenantList(AuthenticationFilter.actor(request))){var data=new LinkedHashMap<String,Object>();
            data.put("id",row.id());data.put("name",row.name());data.put("enable",row.enabled());data.put("enabled",row.enabled());
            data.put("ownerAccountId",row.ownerAccountId());data.put("members",row.members());data.put("workspaceAdmins",row.workspaceAdmins());
            data.put("pendingMembers",row.pendingMembers());data.put("storageSources",row.storageSources());data.put("createTime",row.createTime());records.add(data);}
        return ApiResponse.success(records);
    }

    /** 创建或更新平台空间资料与启用状态。 */
    @PostMapping("/admin/tenant")
    public ApiResponse<PlatformAdministrationService.Workspace> saveTenant(@Valid @RequestBody TenantRequest input,HttpServletRequest request) {
        return ApiResponse.success(administration.saveWorkspace(AuthenticationFilter.actor(request),
                new PlatformAdministrationService.TenantInput(input.id(),input.name(),input.enable())));
    }

    /** 读取管理权限允许查看的单个账号资料。 */
    @GetMapping("/admin/user/{id}")
    public ApiResponse<PlatformAdministrationService.Member> user(@PathVariable long id,HttpServletRequest request) {
        return ApiResponse.success(administration.user(AuthenticationFilter.actor(request),id));
    }

    /** 保存空间管理员资料或本人允许修改的账号信息。 */
    @PostMapping("/admin/user/saveOrUpdate")
    public ApiResponse<PlatformAdministrationService.Member> saveUser(@Valid @RequestBody WorkspaceAdministratorRequest input,HttpServletRequest request) {
        return ApiResponse.success(administration.saveAdministrator(AuthenticationFilter.actor(request),
                new PlatformAdministrationService.AdministratorInput(input.id(),input.username(),input.email(),input.password(),input.oldPassword(),input.nickname(),input.tenantId(),input.role(),input.enable())));
    }

    /** 读取全局管理员可见的站点与界面设置。 */
    @GetMapping("/admin/config")
    public ApiResponse<Map<String,Object>> configuration(HttpServletRequest request) {
        return ApiResponse.success(administration.configuration(AuthenticationFilter.actor(request)));
    }

    /** 提交允许修改的站点及界面设置。 */
    @PutMapping("/admin/config/site")
    public ApiResponse<Void> siteConfiguration(@RequestBody Map<String,Object> input,HttpServletRequest request) {
        administration.updateConfiguration(AuthenticationFilter.actor(request),input,false);return ApiResponse.success(null);
    }

    /** 仅调整空间管理员账号的启用状态。 */
    @PostMapping({"/admin/user/enable/{id}","/admin/user/disable/{id}"})
    public ApiResponse<Void> enableAdministrator(@PathVariable long id,HttpServletRequest request){
        var actor=AuthenticationFilter.actor(request);var user=administration.user(actor,id);
        if(!"TENANT_ADMIN".equals(user.role()))throw new io.studentcloud.common.ApplicationFault("40300",403,"此操作仅用于空间管理员状态");
        boolean enabled=request.getRequestURI().startsWith("/admin/user/enable/");
        administration.saveAdministrator(actor,new PlatformAdministrationService.AdministratorInput(user.id(),user.username(),user.email(),null,null,user.nickname(),user.tenantId(),user.role(),enabled));
        return ApiResponse.success(null);
    }

    /** 提交界面设置白名单内的配置变更。 */
    @PutMapping("/admin/config/view")
    public ApiResponse<Void> viewConfiguration(@RequestBody Map<String,Object> input,HttpServletRequest request) {
        administration.updateConfiguration(AuthenticationFilter.actor(request),input,true);return ApiResponse.success(null);
    }
}

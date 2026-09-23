package im.tlgxy.cloud.module.install.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.install.model.request.InstallSystemRequest;
import im.tlgxy.cloud.module.install.service.InstallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 系统初始化控制器
 * <p>
 * 提供系统首次安装时的初始化接口，包括：
 * <ul>
 *   <li>查询系统是否已初始化的状态接口</li>
 *   <li>执行系统初始化（创建管理员账户、设置站点名称等）的接口</li>
 * </ul>
 * <p>
 * 系统初始化是 TlgxyCloud 部署后的第一步操作，只有初始化完成后才能正常使用系统功能。
 *
 * @author zhaojun
 */
@Tag(name = "初始化模块")
@RestController
@RequestMapping("/api")
public class InstallController {

    /**
     * 系统安装服务，负责具体的初始化逻辑
     */
    @Resource
    private InstallService installService;

    /**
     * 获取系统初始化状态
     * <p>
     * 通过检查管理员用户名是否存在来判断系统是否已完成初始化。
     *
     * @return AjaxJson 包含布尔值，true 表示已初始化，false 表示未初始化
     */
    @GetMapping("/install/status")
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取系统初始化状态", description = "根据管理员用户名是否存在判断系统已初始化, 已初始化返回 true, 未初始化返回 false")
    public AjaxJson<Boolean> isInstall() {
        return AjaxJson.getSuccessData(installService.getSystemIsInstalled());
    }

    /**
     * 执行系统初始化
     * <p>
     * 接收前端传来的初始化参数（站点名称、管理员用户名和密码），完成系统的首次初始化。
     * 该方法标注了 {@link DemoDisable} 注解，在演示模式下会被禁用，防止误操作。
     *
     * @param installSystemRequest 系统初始化请求参数，包含站点名称、用户名和密码
     * @return AjaxJson 操作成功时返回成功状态
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "初始化系统", description = "根据管理员用户名是否存在判断系统已初始化, 已初始化返回 true, 未初始化返回 false")
    @PostMapping("/install")
    @DemoDisable
    public AjaxJson<Void> install(@Valid @RequestBody InstallSystemRequest installSystemRequest) {
        installService.install(installSystemRequest);
        return AjaxJson.getSuccess();
    }

}

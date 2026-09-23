package im.tlgxy.cloud.module.sso.controller;

import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.sso.model.entity.SsoConfig;
import im.tlgxy.cloud.module.sso.model.request.CheckProviderDuplicateRequest;
import im.tlgxy.cloud.module.sso.service.SsoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

/**
 * 单点登录后台管理控制器
 * <p>
 * 提供 SSO（单点登录）服务商配置的后台管理 REST API，包括服务商的增删改查操作。
 * 所有接口路径均以 {@code /admin/sso} 开头，通常需要管理员权限才能访问。
 * </p>
 * <p>
 * 与 {@link SsoController}（处理登录跳转/回调）和 {@link SsoAPIController}（公开列表查询）不同，
 * 本控制器专注于服务商配置的管理操作，支持：
 * </p>
 * <ul>
 *   <li>查看所有已配置的 SSO 服务商</li>
 *   <li>新增或更新 SSO 服务商配置</li>
 *   <li>删除指定的 SSO 服务商配置</li>
 *   <li>检查服务商简称是否重复（用于前端表单校验）</li>
 * </ul>
 *
 * @author OnEvent
 */
@Slf4j
@Tag(name = "单点登录管理接口")
@RestController
@RequestMapping("/admin/sso")
@RequiredArgsConstructor
class SsoManagerController {

    /**
     * SSO 服务层，封装了服务商配置的 CRUD 业务逻辑
     */
    private final SsoService ssoService;

    /**
     * 获取所有 SSO 服务商配置列表
     * <p>
     * 返回系统中所有已保存的 SSO 服务商配置（包括已启用和未启用的），
     * 用于后台管理页面展示和编辑。
     * </p>
     *
     * @return AjaxJson 对象，data 字段为 SSO 服务商配置集合
     */
    @GetMapping("/providers")
    @Operation(summary = "SSO 服务商列表")
    public AjaxJson<Collection<SsoConfig>> list() {
        // 调用服务层获取所有 SSO 服务商配置
        List<SsoConfig> ssoConfigList = ssoService.list();
        return AjaxJson.getSuccessData(ssoConfigList);
    }

    /**
     * 保存或更新 SSO 服务商配置
     * <p>
     * 根据传入的配置对象中是否包含 ID 来判断是新增还是更新操作：
     * 有 ID 则更新已有配置，无 ID 则新增配置。
     * 该方法带有 {@link DemoDisable} 注解，在演示模式下禁止执行。
     * </p>
     *
     * @param ssoConfig SSO 服务商配置实体，包含服务商类型、客户端 ID、客户端密钥等信息，
     *                  参数经过 JSR-303 校验（{@link Valid}）
     * @return AjaxJson 对象，data 字段为保存后的 SSO 服务商配置
     */
    @PostMapping("/provider")
    @Operation(summary = "保存 SSO 服务商")
    @DemoDisable
    public AjaxJson<SsoConfig> saveOrUpdateProvider(@RequestBody @Valid SsoConfig ssoConfig) {
        // 调用服务层执行保存或更新操作，返回持久化后的配置对象
        return AjaxJson.getSuccessData(ssoService.saveOrUpdate(ssoConfig));
    }

    /**
     * 删除指定的 SSO 服务商配置
     * <p>
     * 根据服务商标识（provider）删除对应的配置记录。
     * 该方法带有 {@link DemoDisable} 注解，在演示模式下禁止执行。
     * </p>
     *
     * @param provider 服务商标识（如 github、google、oidc 等），用于定位要删除的配置
     * @return AjaxJson 对象，只包含成功状态，无额外数据
     */
    @DeleteMapping("/provider/{provider}")
    @Operation(summary = "删除 SSO 服务商")
    @DemoDisable
    public AjaxJson<Void> deleteProvider(@PathVariable String provider) {
        // 调用服务层根据服务商标识删除对应配置
        ssoService.deleteProvider(provider);
        return AjaxJson.getSuccess();
    }

    /**
     * 检查服务商简称是否重复
     * <p>
     * 用于新增或编辑 SSO 服务商配置时，在前端实时校验服务商简称是否已被占用。
     * 如果传入的请求中包含 ID，则在检查时会排除自身记录，允许编辑时保持原名不变。
     * </p>
     *
     * @param request 包含待检查的服务商标识 {@code provider} 和可选的配置 ID {@code id}
     * @return AjaxJson 对象，data 字段为布尔值：{@code true} 表示重复，{@code false} 表示不重复
     */
    @GetMapping("/provider/checkDuplicate")
    @Operation(summary = "检查服务商简称是否重复")
    public AjaxJson<Boolean> checkDuplicate(CheckProviderDuplicateRequest request) {
        // 获取配置 ID（新增时为 null，编辑时有值）
        Integer id = request.getId();
        // 获取待校验的服务商标识
        String provider = request.getProvider();
        // 调用服务层检查是否重复，编辑时会排除自身
        return AjaxJson.getSuccessData(ssoService.checkDuplicateProvider(id, provider));
    }

}

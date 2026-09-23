package im.tlgxy.cloud.module.config.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.model.request.*;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 站点设置控制器，提供管理后台各项系统配置的查询和修改接口。
 * <p>
 * 支持的设置项包括：
 * <ul>
 *   <li>站点基本设置（站点名称、授权码等）</li>
 *   <li>显示设置（页面布局、列表尺寸等）</li>
 *   <li>登录安全设置（验证码、双因素认证等）</li>
 *   <li>直链设置（直链前缀、有效期等）</li>
 *   <li>访问控制设置（IP 白名单/黑名单等）</li>
 * </ul>
 *
 * @author zhaojun
 */
@Tag(name = "站点设置模块")
@ApiSort(2)
@RestController
@RequestMapping("/admin")
public class SettingController {

    /**
     * 系统配置服务，用于读取和更新系统配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * TlgxyCloud 全局属性配置，包含调试模式、演示站点等标志位
     */
    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 获取站点全部配置信息。
     * <p>
     * 如果当前是演示站点，会深拷贝一份配置并将敏感信息（授权码、RSA 密钥）置空后再返回，
     * 防止演示环境中泄露敏感数据。
     *
     * @return 包含系统配置 DTO 的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "获取站点信息", description = "获取站点相关信息，如站点名称，风格样式，是否显示公告，是否显示文档区，自定义 CSS，JS 等参数")
    @GetMapping("/config")
    public AjaxJson<SystemConfigDTO> getConfig() {
        SystemConfigDTO systemConfigDTO = systemConfigService.getSystemConfig();
        // 演示站点需要对敏感信息脱敏
        if (tlgxyCloudProperties != null && tlgxyCloudProperties.isDemoSite()) {
            // 通过 JSON 序列化再反序列化实现深拷贝
            SystemConfigDTO copy = JSON.parseObject(JSON.toJSONString(systemConfigDTO), SystemConfigDTO.class);
            copy.setAuthCode(null);
            copy.setRsaHexKey(null);
            return AjaxJson.getSuccessData(copy);
        }
        return AjaxJson.getSuccessData(systemConfigDTO);
    }

    /**
     * 修改站点基本设置（如站点名称、授权码等）。
     * <p>
     * 会对授权码长度进行校验，防止复制时带入多余的空格或特殊字符。
     *
     * @param settingRequest 站点设置请求参数
     * @return 操作成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 3)
    @Operation(summary = "修改站点设置")
    @PutMapping("/config/site")
    @SaCheckRole("admin")
    @DemoDisable
    public AjaxJson<Void> updateSiteSetting(@Valid @RequestBody UpdateSiteSettingRequest settingRequest) {
        // 校验授权码长度，避免意外复制空格或特殊字符
        if (StrUtil.length(settingRequest.getAuthCode()) > 36 && StrUtil.length(settingRequest.getAuthCode()) < 100) {
            throw new BizException("授权码长度异常，请检查是否额外复制了空格或特殊字符！");
        }
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        BeanUtils.copyProperties(settingRequest, systemConfigDTO);
        systemConfigService.updateSystemConfig(systemConfigDTO);
        return AjaxJson.getSuccess();
    }

    /**
     * 修改显示设置（如页面布局、列表尺寸、自定义 CSS/JS 等）。
     *
     * @param settingRequest 显示设置请求参数
     * @return 操作成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 4)
    @Operation(summary = "修改显示设置")
    @PutMapping("/config/view")
    @SaCheckRole("admin")
    @DemoDisable
    public AjaxJson<Void> updateViewSetting(@Valid @RequestBody UpdateViewSettingRequest settingRequest) {
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        BeanUtils.copyProperties(settingRequest, systemConfigDTO);
        systemConfigService.updateSystemConfig(systemConfigDTO);
        return AjaxJson.getSuccess();
    }

    /**
     * 修改登录安全设置（如登录验证方式、双因素认证等）。
     * <p>
     * 当管理员关闭双因素认证时，会清空已保存的 TOTP 密钥。
     *
     * @param settingRequest 安全设置请求参数
     * @return 操作成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 5)
    @Operation(summary = "修改登陆安全设置")
    @PutMapping("/config/security")
    @SaCheckRole("admin")
    @DemoDisable
    public AjaxJson<Void> updateSecuritySetting(@Valid @RequestBody UpdateSecuritySettingRequest settingRequest) {
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        BeanUtils.copyProperties(settingRequest, systemConfigDTO);
        // 如果关闭了双因素认证，则清空已保存的 TOTP 密钥
        if (BooleanUtils.isNotTrue(settingRequest.getAdminTwoFactorVerify())) {
            systemConfigDTO.setLoginVerifySecret("");
        }
        systemConfigService.updateSystemConfig(systemConfigDTO);
        return AjaxJson.getSuccess();
    }

    /**
     * 修改直链设置（如直链前缀、直链有效期等）。
     *
     * @param settingRequest 直链设置请求参数
     * @return 操作成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 6)
    @Operation(summary = "修改直链设置")
    @PutMapping("/config/link")
    @SaCheckRole("admin")
    @DemoDisable
    public AjaxJson<Void> updateLinkSetting(@Valid @RequestBody UpdateLinkSettingRequest settingRequest) {
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        BeanUtils.copyProperties(settingRequest, systemConfigDTO);
        systemConfigService.updateSystemConfig(systemConfigDTO);
        return AjaxJson.getSuccess();
    }

    /**
     * 修改访问控制设置（如 IP 白名单/黑名单、访问频率限制等）。
     *
     * @param updateAccessSettingRequest 访问控制设置请求参数
     * @return 操作成功的 AjaxJson 响应
     */
    @ApiOperationSupport(order = 7)
    @Operation(summary = "修改访问控制设置")
    @PutMapping("/config/access")
    @SaCheckRole("admin")
    @DemoDisable
    public AjaxJson<Void> updateSecuritySetting(@Valid @RequestBody UpdateAccessSettingRequest updateAccessSettingRequest) {
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        BeanUtils.copyProperties(updateAccessSettingRequest, systemConfigDTO);
        systemConfigService.updateSystemConfig(systemConfigDTO);
        return AjaxJson.getSuccess();
    }

}

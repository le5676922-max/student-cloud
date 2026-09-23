package im.tlgxy.cloud.module.storage.service.impl;

import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.SharePointChinaParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractSharePointServiceBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 世纪互联版 SharePoint（中国版 Office 365）存储服务实现类。
 * <p>
 * 世纪互联运营的 Office 365 使用独立的 Azure 云，其 Microsoft Graph API 和
 * 认证端点与国际版不同。本类继承自 {@link AbstractSharePointServiceBase}，
 * 重写了 Graph 端点、认证端点以及 OAuth 2.0 客户端配置，
 * 以适配中国版 Office 365 的 API 地址和认证流程。
 * </p>
 * <p>
 * 各端点地址说明：
 * <ul>
 *   <li>Graph API 端点：microsoftgraph.chinacloudapi.cn（中国版 Microsoft Graph）</li>
 *   <li>认证端点：login.partner.microsoftonline.cn（中国版 Azure AD 认证）</li>
 * </ul>
 * </p>
 * <p>
 * 如果用户未单独配置 ClientId/ClientSecret/RedirectUri，则使用
 * {@link TlgxyCloudProperties} 中全局配置的世纪互联 OneDrive 应用注册信息。
 * </p>
 * <p>
 * 该 Bean 为原型作用域（PROTOTYPE），每次注入都会创建新实例，
 * 以便支持多个不同的 SharePoint 站点配置同时存在。
 * </p>
 *
 * @author zhaojun
 */
@Service
@Slf4j
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SharePointChinaServiceImpl extends AbstractSharePointServiceBase<SharePointChinaParam> {

    /**
     * TlgxyCloud 全局配置属性，用于获取世纪互联版 Office 365 的默认 OAuth 配置
     */
    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#SHAREPOINT_DRIVE_CHINA}，表示中国版 SharePoint 存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.SHAREPOINT_DRIVE_CHINA;
    }

    /**
     * 获取 Microsoft Graph API 的端点地址。
     * <p>
     * 中国版（世纪互联）Office 365 使用独立的 Graph API 端点。
     * </p>
     *
     * @return 中国版 Graph API 端点地址 "microsoftgraph.chinacloudapi.cn"
     */
    @Override
    public String getGraphEndPoint() {
        return "microsoftgraph.chinacloudapi.cn";
    }

    /**
     * 获取 Azure AD 认证端点地址。
     * <p>
     * 中国版（世纪互联）Office 365 使用独立的认证端点。
     * </p>
     *
     * @return 中国版认证端点地址 "login.partner.microsoftonline.cn"
     */
    @Override
    public String getAuthenticateEndPoint() {
        return "login.partner.microsoftonline.cn";
    }

    /**
     * 获取 OAuth 2.0 客户端 ID。
     * <p>
     * 优先使用存储源参数中配置的 ClientId，
     * 如果未配置则使用全局配置中的世纪互联版 ClientId。
     * </p>
     *
     * @return OAuth 客户端 ID
     */
    @Override
    public String getClientId() {
        if (param == null || param.getClientId() == null) {
            return tlgxyCloudProperties.getOnedriveChina().getClientId();
        }
        return param.getClientId();
    }

    /**
     * 获取 OAuth 2.0 重定向 URI。
     * <p>
     * 优先使用存储源参数中配置的 RedirectUri，
     * 如果未配置则使用全局配置中的世纪互联版 RedirectUri。
     * </p>
     *
     * @return OAuth 重定向 URI
     */
    @Override
    public String getRedirectUri() {
        if (param == null || param.getRedirectUri() == null) {
            return tlgxyCloudProperties.getOnedriveChina().getRedirectUri();
        }
        return param.getRedirectUri();
    }

    /**
     * 获取 OAuth 2.0 客户端密钥。
     * <p>
     * 优先使用存储源参数中配置的 ClientSecret，
     * 如果未配置则使用全局配置中的世纪互联版 ClientSecret。
     * </p>
     *
     * @return OAuth 客户端密钥
     */
    @Override
    public String getClientSecret() {
        if (param == null || param.getClientSecret() == null) {
            return tlgxyCloudProperties.getOnedriveChina().getClientSecret();
        }
        return param.getClientSecret();
    }

    /**
     * 获取 OAuth 2.0 权限作用域。
     * <p>
     * 直接使用全局配置中的世纪互联版 Scope，
     * 不支持在存储源参数中单独配置。
     * </p>
     *
     * @return OAuth 权限作用域字符串
     */
    @Override
    public String getScope() {
        return tlgxyCloudProperties.getOnedriveChina().getScope();
    }

}
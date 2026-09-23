package im.tlgxy.cloud.module.storage.service.impl;

import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.SharePointParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractSharePointServiceBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 国际版 SharePoint（Microsoft 365）存储服务实现类。
 * <p>
 * 基于 Microsoft Graph API 和 Azure AD 认证，提供对 SharePoint 站点文档库
 * 的文件访问、上传、下载等操作。本类继承自 {@link AbstractSharePointServiceBase}，
 * 使用国际版（全球版）的 Graph API 端点和 Azure AD 认证端点。
 * </p>
 * <p>
 * 各端点地址说明：
 * <ul>
 *   <li>Graph API 端点：graph.microsoft.com（国际版 Microsoft Graph）</li>
 *   <li>认证端点：login.microsoftonline.com（国际版 Azure AD 认证）</li>
 * </ul>
 * </p>
 * <p>
 * 如果用户未单独配置 ClientId/ClientSecret/RedirectUri，则使用
 * {@link TlgxyCloudProperties} 中全局配置的国际版 OneDrive 应用注册信息。
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
public class SharePointServiceImpl extends AbstractSharePointServiceBase<SharePointParam> {

    /**
     * TlgxyCloud 全局配置属性，用于获取国际版 Office 365 的默认 OAuth 配置
     */
    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 获取当前存储服务的类型枚举。
     *
     * @return 返回 {@link StorageTypeEnum#SHAREPOINT_DRIVE}，表示国际版 SharePoint 存储类型
     */
    @Override
    public StorageTypeEnum getStorageTypeEnum() {
        return StorageTypeEnum.SHAREPOINT_DRIVE;
    }

    /**
     * 获取 Microsoft Graph API 的端点地址。
     * <p>
     * 国际版 Office 365 使用全球版 Graph API 端点。
     * </p>
     *
     * @return 国际版 Graph API 端点地址 "graph.microsoft.com"
     */
    @Override
    public String getGraphEndPoint() {
        return "graph.microsoft.com";
    }

    /**
     * 获取 Azure AD 认证端点地址。
     * <p>
     * 国际版 Office 365 使用全球版 Azure AD 认证端点。
     * </p>
     *
     * @return 国际版认证端点地址 "login.microsoftonline.com"
     */
    @Override
    public String getAuthenticateEndPoint() {
        return "login.microsoftonline.com";
    }

    /**
     * 获取 OAuth 2.0 客户端 ID。
     * <p>
     * 优先使用存储源参数中配置的 ClientId，
     * 如果未配置则使用全局配置中的国际版 ClientId。
     * </p>
     *
     * @return OAuth 客户端 ID
     */
    @Override
    public String getClientId() {
        if (param == null || param.getClientId() == null) {
            return tlgxyCloudProperties.getOnedrive().getClientId();
        }
        return param.getClientId();
    }

    /**
     * 获取 OAuth 2.0 重定向 URI。
     * <p>
     * 优先使用存储源参数中配置的 RedirectUri，
     * 如果未配置则使用全局配置中的国际版 RedirectUri。
     * </p>
     *
     * @return OAuth 重定向 URI
     */
    @Override
    public String getRedirectUri() {
        if (param == null || param.getRedirectUri() == null) {
            return tlgxyCloudProperties.getOnedrive().getRedirectUri();
        }
        return param.getRedirectUri();
    }

    /**
     * 获取 OAuth 2.0 客户端密钥。
     * <p>
     * 优先使用存储源参数中配置的 ClientSecret，
     * 如果未配置则使用全局配置中的国际版 ClientSecret。
     * </p>
     *
     * @return OAuth 客户端密钥
     */
    @Override
    public String getClientSecret() {
        if (param == null || param.getClientSecret() == null) {
            return tlgxyCloudProperties.getOnedrive().getClientSecret();
        }
        return param.getClientSecret();
    }

    /**
     * 获取 OAuth 2.0 权限作用域。
     * <p>
     * 直接使用全局配置中的国际版 Scope，
     * 不支持在存储源参数中单独配置。
     * </p>
     *
     * @return OAuth 权限作用域字符串
     */
    @Override
    public String getScope() {
        return tlgxyCloudProperties.getOnedrive().getScope();
    }

}
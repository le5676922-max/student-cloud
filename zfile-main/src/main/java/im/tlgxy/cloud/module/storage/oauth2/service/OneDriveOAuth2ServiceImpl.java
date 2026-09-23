package im.tlgxy.cloud.module.storage.oauth2.service;

import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * OneDrive 国际版 OAuth2 认证服务实现类
 * <p>
 * 为 OneDrive 国际版（包括个人版和商业版）提供 OAuth2 认证配置。
 * 使用全球版的 Microsoft 认证端点 "login.microsoftonline.com"。
 * </p>
 * <p>
 * 继承自 {@link AbstractMicrosoftOAuth2Service}，通过重写抽象方法
 * 提供国际版特有的端点地址和客户端凭据。
 * </p>
 *
 * @author zhaojun
 */
@Component
public class OneDriveOAuth2ServiceImpl extends AbstractMicrosoftOAuth2Service {

    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 获取国际版 Microsoft 认证端点地址
     *
     * @return "login.microsoftonline.com"
     */
    @Override
    public String getEndPoint() {
        return "login.microsoftonline.com";
    }

    /**
     * 获取国际版 OAuth2 客户端 ID
     *
     * @return 配置文件中的国际版客户端 ID
     */
    @Override
    public String getClientId() {
        return tlgxyCloudProperties.getOnedrive().getClientId();
    }

    /**
     * 获取国际版 OAuth2 客户端密钥
     *
     * @return 配置文件中的国际版客户端密钥
     */
    @Override
    public String getClientSecret() {
        return tlgxyCloudProperties.getOnedrive().getClientSecret();
    }

    /**
     * 获取国际版 OAuth2 授权回调地址
     *
     * @return 配置文件中的国际版回调地址
     */
    @Override
    public String getRedirectUri() {
        return tlgxyCloudProperties.getOnedrive().getRedirectUri();
    }

    /**
     * 获取国际版 OAuth2 权限范围
     *
     * @return 配置文件中的国际版权限范围
     */
    @Override
    public String getScope() {
        return tlgxyCloudProperties.getOnedrive().getScope();
    }
}

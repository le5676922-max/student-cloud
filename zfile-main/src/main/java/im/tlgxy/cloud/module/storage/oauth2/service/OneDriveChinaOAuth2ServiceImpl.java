package im.tlgxy.cloud.module.storage.oauth2.service;

import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * OneDrive 世纪互联版 OAuth2 认证服务实现类
 * <p>
 * 专门为 OneDrive 世纪互联版（由世纪互联运营的 Office 365）
 * 提供 OAuth2 认证配置。
 * 世纪互联版使用独立的认证端点 "login.partner.microsoftonline.cn"，
 * 与其他版本在数据存储和认证服务上完全独立。
 * </p>
 * <p>
 * 继承自 {@link AbstractMicrosoftOAuth2Service}，通过重写抽象方法
 * 提供世纪互联版特有的端点地址和客户端凭据。
 * </p>
 *
 * @author zhaojun
 */
@Component
public class OneDriveChinaOAuth2ServiceImpl extends AbstractMicrosoftOAuth2Service {

    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 获取世纪互联版 Microsoft 认证端点地址
     *
     * @return "login.partner.microsoftonline.cn"
     */
    @Override
    public String getEndPoint() {
        return "login.partner.microsoftonline.cn";
    }

    /**
     * 获取世纪互联版 OAuth2 客户端 ID
     *
     * @return 配置文件中的世纪互联版客户端 ID
     */
    @Override
    public String getClientId() {
        return tlgxyCloudProperties.getOnedriveChina().getClientId();
    }

    /**
     * 获取世纪互联版 OAuth2 客户端密钥
     *
     * @return 配置文件中的世纪互联版客户端密钥
     */
    @Override
    public String getClientSecret() {
        return tlgxyCloudProperties.getOnedriveChina().getClientSecret();
    }

    /**
     * 获取世纪互联版 OAuth2 授权回调地址
     *
     * @return 配置文件中的世纪互联版回调地址
     */
    @Override
    public String getRedirectUri() {
        return tlgxyCloudProperties.getOnedriveChina().getRedirectUri();
    }

    /**
     * 获取世纪互联版 OAuth2 权限范围
     *
     * @return 配置文件中的世纪互联版权限范围
     */
    @Override
    public String getScope() {
        return tlgxyCloudProperties.getOnedriveChina().getScope();
    }
}

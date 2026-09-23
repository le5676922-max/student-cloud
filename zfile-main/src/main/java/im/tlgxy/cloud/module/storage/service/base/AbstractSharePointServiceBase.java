package im.tlgxy.cloud.module.storage.service.base;

import im.tlgxy.cloud.module.storage.model.param.SharePointParam;

/**
 * SharePoint 站点存储源抽象基础服务类。
 * <p>
 * 该类继承自 {@link AbstractMicrosoftDriveService}，专门用于处理 SharePoint 类型的存储源。
 * SharePoint 与 OneDrive 共用相同的 Microsoft Graph API，但它们的资源标识方式不同：
 * <ul>
 *   <li>OneDrive 使用 "me" 代表当前登录用户的个人 OneDrive</li>
 *   <li>SharePoint 使用 "sites/{siteId}" 代表指定的 SharePoint 站点</li>
 * </ul>
 * </p>
 * <p>
 * {@link #getType()} 方法返回 "sites/" + {@link SharePointParam#getSiteId()}，
 * 用于构建 Microsoft Graph API URL 中的 {type} 占位符，
 * 使得所有父类中的 API 调用都能正确指向指定的 SharePoint 站点。
 * </p>
 * <p>
 * 子类只需实现与具体 SharePoint 版本（国际版 / 世纪互联版等）相关的抽象方法：
 * graphEndPoint、authenticateEndPoint、clientId、redirectUri、clientSecret、scope 等。
 * </p>
 *
 * @param <P> 存储源参数类型，必须继承自 {@link SharePointParam}
 * @author zhaojun
 */
public abstract class AbstractSharePointServiceBase<P extends SharePointParam> extends AbstractMicrosoftDriveService<SharePointParam> {

    /**
     * 获取 SharePoint 站点的存储类型标识。
     * <p>
     * 在 Microsoft Graph API URL 中，SharePoint 使用 "sites/{siteId}" 格式作为资源标识，
     * 其中 siteId 来自存储源参数中配置的 SharePoint 站点 ID。
     * </p>
     *
     * @return SharePoint 站点标识，格式为 "sites/{siteId}"
     */
    @Override
    public String getType() {
        return "sites/" + param.getSiteId();
    }

}
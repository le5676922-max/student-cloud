package im.tlgxy.cloud.module.storage.service.base;

import im.tlgxy.cloud.module.storage.model.param.OneDriveParam;
import lombok.extern.slf4j.Slf4j;

/**
 * OneDrive 存储源抽象基础服务类。
 * <p>
 * 该类继承自 {@link AbstractMicrosoftDriveService}，专门用于处理 OneDrive 类型的存储源。
 * 与 SharePoint 不同，OneDrive 的 Graph API 中存储类型固定为 "me"（代表当前登录用户）。
 * </p>
 * <p>
 * 子类只需实现与具体 OneDrive 版本（国际版 / 世纪互联版等）相关的抽象方法：
 * <ul>
 *   <li>{@link #getGraphEndPoint()} - Graph API 终结点地址</li>
 *   <li>{@link #getAuthenticateEndPoint()} - OAuth2 认证终结点地址</li>
 *   <li>{@link #getClientId()} - 应用程序 ID</li>
 *   <li>{@link #getRedirectUri()} - 重定向地址</li>
 *   <li>{@link #getClientSecret()} - 应用程序密钥</li>
 *   <li>{@link #getScope()} - API 权限范围</li>
 * </ul>
 * </p>
 *
 * @param <P> 存储源参数类型，必须继承自 {@link OneDriveParam}
 * @author zhaojun
 */
@Slf4j
public abstract class AbstractOneDriveServiceBase<P extends OneDriveParam> extends AbstractMicrosoftDriveService<P> {

    /**
     * 获取 OneDrive 的存储类型标识。
     * <p>
     * 对于 OneDrive，在 Microsoft Graph API URL 中固定使用 "me" 作为资源标识，
     * 代表当前已认证用户（资源所有者）的 OneDrive。
     * </p>
     *
     * @return 固定返回 "me"
     */
    @Override
    public String getType() {
        return "me";
    }

}
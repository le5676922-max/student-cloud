package im.tlgxy.cloud.module.config.event;

import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import im.tlgxy.cloud.module.user.service.DynamicLoginEntryService;
import im.tlgxy.cloud.module.user.util.LoginEntryPathUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * 安全登录入口修改事件处理器。
 * <p>
 * 当管理员在后台修改安全登录入口路径配置时，此处理器会自动更新 Spring MVC 中登录接口的 URL 映射，
 * 确保新的登录路径立即生效，无需重启应用。
 * <p>
 * 安全登录入口功能允许管理员自定义后台登录页面的访问路径，提高系统安全性。
 *
 * @author zhaojun
 */
@Slf4j
@Component
public class SecureLoginEntryModifyHandler implements ISystemConfigModifyHandler {

    /**
     * 动态登录入口服务，用于运行时注册/更新登录接口的 URL 映射
     */
    @Resource
    private DynamicLoginEntryService dynamicLoginEntryService;

    /**
     * 处理安全登录入口配置变更事件。
     * <p>
     * 解析新旧配置值的实际登录路径，如果路径发生变化则动态更新 Spring MVC 的请求映射。
     *
     * @param originalSystemConfig 修改前的系统配置
     * @param newSystemConfig      修改后的系统配置
     */
    @Override
    public void modify(SystemConfig originalSystemConfig, SystemConfig newSystemConfig) {
        // 解析修改前后的实际登录路径
        String oldPath = LoginEntryPathUtils.resolveLoginPath(originalSystemConfig.getValue());
        String newPath = LoginEntryPathUtils.resolveLoginPath(newSystemConfig.getValue());

        // 路径未变化则跳过处理
        if (StringUtils.equals(oldPath, newPath)) {
            log.info("检测到修改安全登录入口，但实际登录路径未变化，跳过处理。");
            return;
        }

        // 构建新的登录接口请求映射并动态注册
        RequestMappingInfo requestMappingInfo = dynamicLoginEntryService.buildLoginRequestMappingInfo(newSystemConfig.getValue());
        dynamicLoginEntryService.updateRegisterMappingHandler(SystemConfig.SECURE_LOGIN_ENTRY_NAME, requestMappingInfo);
        log.info("安全登录入口已更新，{} -> {}", oldPath, newPath);
    }

    /**
     * 判断当前处理器是否匹配指定的配置项名称。
     *
     * @param name 配置项名称
     * @return 当配置项为安全登录入口名称时返回 true
     */
    @Override
    public boolean matches(String name) {
        return SystemConfig.SECURE_LOGIN_ENTRY_NAME.equals(name);
    }

}
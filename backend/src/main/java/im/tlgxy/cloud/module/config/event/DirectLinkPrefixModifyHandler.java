package im.tlgxy.cloud.module.config.event;

import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import im.tlgxy.cloud.module.link.controller.DirectLinkController;
import im.tlgxy.cloud.module.link.service.DynamicDirectLinkPrefixService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * 直链前缀修改事件处理器。
 * <p>
 * 当系统设置中的直链前缀配置项发生变更时，此处理器会自动更新 Spring MVC 的请求映射，
 * 使新的直链前缀立即生效，无需重启应用。
 * <p>
 * 实现了 {@link ISystemConfigModifyHandler} 接口，由 {@link SystemConfigModifyHandlerChain} 统一调度。
 *
 * @author zhaojun
 */
@Slf4j
@Component
public class DirectLinkPrefixModifyHandler implements ISystemConfigModifyHandler {

    /**
     * 动态直链前缀服务，用于运行时注册/更新直链的 URL 映射
     */
    @Resource
    private DynamicDirectLinkPrefixService dynamicDirectLinkPrefixService;

    /**
     * 处理直链前缀配置变更事件。
     * <p>
     * 当新旧值相同时不做任何处理。当值发生变化时，构建新的请求映射信息并更新到 Spring MVC 中。
     *
     * @param originalSystemConfig 修改前的系统配置
     * @param newSystemConfig      修改后的系统配置
     */
    @Override
    public void modify(SystemConfig originalSystemConfig, SystemConfig newSystemConfig) {
        String oldValue = originalSystemConfig.getValue();
        String newValue = newSystemConfig.getValue();
        // 新旧值相同，无需更新
        if (StringUtils.equals(oldValue, newValue)) {
            log.info("检测到修改了直链前缀, 但是新值和旧值相同, 不做处理.");
            return;
        }

        // 构建新的请求映射路径：直链前缀 + 直链接口后缀
        RequestMappingInfo requestMappingInfo = RequestMappingInfo.paths(newValue + DirectLinkController.DIRECT_LINK_SUFFIX_PATH).build();
        // 动态更新 Spring MVC 中的 URL 映射
        dynamicDirectLinkPrefixService.updateRegisterMappingHandler(SystemConfig.DIRECT_LINK_PREFIX_NAME, requestMappingInfo);
        log.info("检测到修改了直链前缀, [{}] -> [{}], 已自动更新直链前缀.", oldValue, newValue);
    }

    /**
     * 判断当前处理器是否匹配指定的配置项名称。
     *
     * @param name 配置项名称
     * @return 当配置项为直链前缀名称时返回 true
     */
    @Override
    public boolean matches(String name) {
        return SystemConfig.DIRECT_LINK_PREFIX_NAME.equals(name);
    }

}

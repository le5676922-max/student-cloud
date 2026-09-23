package im.tlgxy.cloud.module.config.event;

import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 系统配置修改事件责任链。
 * <p>
 * 采用责任链模式，在系统配置发生变更时，遍历所有已注册的 {@link ISystemConfigModifyHandler} 处理器，
 * 找到匹配当前配置项名称的处理器并执行其修改逻辑。
 * <p>
 * Spring 会自动收集所有实现了 {@link ISystemConfigModifyHandler} 接口的 Bean 注入到 handlers 列表中。
 * 当前已注册的处理器包括：
 * <ul>
 *   <li>{@link DirectLinkPrefixModifyHandler} —— 处理直链前缀变更</li>
 *   <li>{@link SecureLoginEntryModifyHandler} —— 处理安全登录入口变更</li>
 * </ul>
 *
 * @author zhaojun
 */
@Component
public class SystemConfigModifyHandlerChain {

    /**
     * 所有实现了 ISystemConfigModifyHandler 接口的处理器列表，由 Spring 自动注入
     */
    @Resource
    private List<ISystemConfigModifyHandler> handlers;

    /**
     * 执行配置变更处理链。
     * <p>
     * 遍历所有处理器，筛选出匹配当前配置项名称的处理器，依次执行其 modify 方法。
     *
     * @param originalSystemConfig 修改前的系统配置实体
     * @param newSystemConfig      修改后的系统配置实体
     */
    public void execute(SystemConfig originalSystemConfig, SystemConfig newSystemConfig) {
        handlers.stream()
                // 筛选出匹配当前配置项名称的处理器
                .filter(handler -> handler.matches(originalSystemConfig.getName()))
                // 依次执行处理逻辑
                .forEach(handler -> handler.modify(originalSystemConfig, newSystemConfig));
    }

}
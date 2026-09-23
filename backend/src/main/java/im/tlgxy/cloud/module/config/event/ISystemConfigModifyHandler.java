package im.tlgxy.cloud.module.config.event;

import im.tlgxy.cloud.module.config.model.entity.SystemConfig;

/**
 * 系统配置修改事件处理器接口。
 * <p>
 * 所有需要对特定系统配置项变更做出响应的处理器都应实现此接口。
 * 通过 {@link SystemConfigModifyHandlerChain} 责任链统一管理和调度。
 * <p>
 * 典型实现包括：
 * <ul>
 *   <li>{@link DirectLinkPrefixModifyHandler} —— 直链前缀变更时动态更新 URL 映射</li>
 *   <li>{@link SecureLoginEntryModifyHandler} —— 安全登录入口变更时动态更新登录路径</li>
 * </ul>
 *
 * @author zhaojun
 */
public interface ISystemConfigModifyHandler {

    /**
     * 当匹配的配置项发生修改时触发此方法。
     * <p>
     * 实现类在此方法中编写具体的变更处理逻辑，如动态注册 URL 映射、刷新缓存等。
     *
     * @param originalSystemConfig 修改前配置实体，包含旧的配置名称和值
     * @param newSystemConfig      修改后配置实体，包含新的配置名称和值
     */
    void modify(SystemConfig originalSystemConfig, SystemConfig newSystemConfig);

    /**
     * 判断当前处理器是否负责处理指定的配置项。
     * <p>
     * 责任链会遍历所有处理器，调用此方法找到匹配的处理器并执行其 modify 方法。
     *
     * @param name 配置项名称（对应数据库中的 name 字段）
     * @return 如果当前处理器匹配该配置项名称则返回 true，否则返回 false
     */
    boolean matches(String name);

}

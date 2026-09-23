package im.tlgxy.cloud.module.link.service;

import im.tlgxy.cloud.module.link.dto.DynamicRegisterMappingHandlerDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态请求映射服务
 * <p>
 * 用于在运行时动态注册、修改和注销 Spring MVC 的 {@code @RequestMapping} 注解方法。
 * 核心功能是让直链前缀（directLinkPrefix）可以在系统配置中动态修改，
 * 无需重启应用即可更改直链的 URL 访问路径。
 * <p>
 * 工作原理：
 * <ol>
 *   <li>应用启动时，从系统配置中读取直链前缀</li>
 *   <li>调用 {@link #registerMappingHandlerMapping} 将直链处理方法注册到 Spring MVC</li>
 *   <li>当用户修改直链前缀配置时，调用 {@link #updateRegisterMappingHandler} 先注销旧映射再注册新映射</li>
 * </ol>
 * <p>
 * 使用 {@link ConcurrentHashMap} 存储已注册的映射信息，确保线程安全。
 * 更新映射时使用 {@code synchronized} 对 key 加锁，防止并发更新冲突。
 *
 * @author zhaojun
 */
@Slf4j
@Service
public class DynamicDirectLinkPrefixService {

    /**
     * Spring MVC 的请求映射处理器，用于注册和注销请求映射
     */
    @Resource
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    /**
     * 已注册的映射信息存储表，key 为映射名称，value 为映射处理器 DTO。
     * 使用 {@link ConcurrentHashMap} 保证线程安全。
     */
    public static final Map<String, DynamicRegisterMappingHandlerDTO> REGISTER_MAPPING = new ConcurrentHashMap<>();

    /**
     * 注册一个新的请求映射
     * <p>
     * 将指定的 URL 映射信息、控制器对象和处理方法注册到 Spring MVC 中，
     * 使该映射可以处理对应的 HTTP 请求。注册信息同时保存在本地 Map 中，
     * 供后续更新或注销时使用。
     *
     * @param key                 映射的唯一标识名称（如直链前缀名称）
     * @param requestMappingInfo  请求映射信息，包含 URL 模式等映射规则
     * @param controllerObj       处理请求的控制器实例
     * @param directLinkMethod    控制器中处理请求的具体方法
     */
    public void registerMappingHandlerMapping(String key, RequestMappingInfo requestMappingInfo, Object controllerObj, Method directLinkMethod) {
        // 注册到 Spring MVC 的 RequestMappingHandlerMapping
        requestMappingHandlerMapping.registerMapping(requestMappingInfo, controllerObj, directLinkMethod);
        // 保存映射信息到本地 Map，供后续更新使用
        REGISTER_MAPPING.put(key, new DynamicRegisterMappingHandlerDTO(requestMappingInfo, controllerObj, directLinkMethod));

    }

    /**
     * 更新已注册的请求映射
     * <p>
     * 当系统配置发生变更时（如用户修改了直链前缀），调用此方法更新 URL 映射。
     * 更新过程：
     * <ol>
     *   <li>从本地 Map 中查找已注册的映射信息</li>
     *   <li>先注销旧的请求映射</li>
     *   <li>再注册新的请求映射（使用原有的控制器和方法，新的 URL 映射信息）</li>
     *   <li>更新本地 Map 中的映射记录</li>
     * </ol>
     * <p>
     * 使用 {@code synchronized} 对 key 字符串加锁，防止并发更新同一映射时出现竞态条件。
     *
     * @param key                 映射的唯一标识名称
     * @param requestMappingInfo  新的请求映射信息（包含更新后的 URL 模式）
     */
    public void updateRegisterMappingHandler(String key, RequestMappingInfo requestMappingInfo) {
        // 对 key 字符串加锁，防止并发更新冲突
        synchronized (key.intern()) {
            DynamicRegisterMappingHandlerDTO dynamicRegisterMappingHandlerDTO = REGISTER_MAPPING.get(key);
            if (dynamicRegisterMappingHandlerDTO != null) {
                // 注销旧的请求映射
                requestMappingHandlerMapping.unregisterMapping(dynamicRegisterMappingHandlerDTO.getRequestMappingInfo());
                // 注册新的请求映射（使用原有的控制器对象和方法）
                requestMappingHandlerMapping.registerMapping(requestMappingInfo, dynamicRegisterMappingHandlerDTO.getObject(), dynamicRegisterMappingHandlerDTO.getMethod());
                // 更新本地 Map 中的映射记录
                REGISTER_MAPPING.put(key, new DynamicRegisterMappingHandlerDTO(requestMappingInfo, dynamicRegisterMappingHandlerDTO.getObject(), dynamicRegisterMappingHandlerDTO.getMethod()));
            }
        }
    }

}
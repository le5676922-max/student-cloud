package im.tlgxy.cloud.module.user.service;

import im.tlgxy.cloud.module.link.dto.DynamicRegisterMappingHandlerDTO;
import im.tlgxy.cloud.module.user.util.LoginEntryPathUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.http.MediaType;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态登录入口注册服务
 * <p>
 * 负责在运行时动态注册或更新登录接口的请求映射（Request Mapping）。
 * 由于系统支持自定义登录入口路径（如 /login 可改为 /my-secret-login），
 * 需要在运行期间动态修改 Spring MVC 的请求映射关系。
 * </p>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>注册新的登录请求映射（绑定路径、控制器和方法）</li>
 *   <li>更新已有的登录请求映射（先注销旧的、再注册新的）</li>
 *   <li>根据配置构建 RequestMappingInfo 对象</li>
 * </ul>
 * </p>
 * <p>
 * 使用 ConcurrentHashMap 保证线程安全，更新操作使用字符串锁（synchronized）防止并发修改。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Service
public class DynamicLoginEntryService {

    /**
     * Spring MVC 的请求映射处理器
     * <p>用于在运行时动态注册和注销 Controller 方法的请求映射。</p>
     */
    @Resource
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    /**
     * 已注册的动态映射缓存
     * <p>键为映射标识（如 "login"），值为包含映射信息、控制器实例和方法的 DTO。
     * 使用 ConcurrentHashMap 保证多线程环境下的读写安全。</p>
     */
    private static final Map<String, DynamicRegisterMappingHandlerDTO> REGISTER_MAPPING = new ConcurrentHashMap<>();

    /**
     * 注册动态请求映射
     * <p>
     * 将指定的控制器方法注册到 Spring MVC 的请求映射表中，
     * 使其能够处理匹配该 RequestMappingInfo 的 HTTP 请求。
     * 同时将映射信息缓存到 REGISTER_MAPPING 中，便于后续更新或注销。
     * </p>
     *
     * @param key                  映射标识（用于后续查找和更新，如 "login"）
     * @param requestMappingInfo   请求映射信息（包含路径、HTTP 方法、Content-Type 等）
     * @param controllerObj        控制器对象实例
     * @param method               处理请求的目标方法
     */
    public void registerMappingHandlerMapping(String key, RequestMappingInfo requestMappingInfo, Object controllerObj, Method method) {
        // 向 Spring MVC 注册请求映射
        requestMappingHandlerMapping.registerMapping(requestMappingInfo, controllerObj, method);
        // 缓存映射信息，供后续更新或注销使用
        REGISTER_MAPPING.put(key, new DynamicRegisterMappingHandlerDTO(requestMappingInfo, controllerObj, method));
    }

    /**
     * 更新已有的动态请求映射
     * <p>
     * 先注销旧的请求映射（避免路径冲突），再注册新的请求映射。
     * 使用 synchronized 对 key 进行加锁，防止同一映射被并发更新导致不一致。
     * 如果 key 对应的映射不存在，则记录警告日志并跳过。
     * </p>
     *
     * @param key                  映射标识（如 "login"）
     * @param requestMappingInfo   新的请求映射信息
     */
    public void updateRegisterMappingHandler(String key, RequestMappingInfo requestMappingInfo) {
        // 对 key 的 intern 字符串加锁，确保同一 key 的更新操作串行执行
        synchronized (key.intern()) {
            // 获取已注册的旧映射信息
            DynamicRegisterMappingHandlerDTO dynamicRegisterMappingHandlerDTO = REGISTER_MAPPING.get(key);
            if (dynamicRegisterMappingHandlerDTO != null) {
                // 注销旧的请求映射，避免路径冲突
                requestMappingHandlerMapping.unregisterMapping(dynamicRegisterMappingHandlerDTO.getRequestMappingInfo());
                // 注册新的请求映射（控制器和方法保持不变，只更新路径等信息）
                requestMappingHandlerMapping.registerMapping(requestMappingInfo, dynamicRegisterMappingHandlerDTO.getObject(), dynamicRegisterMappingHandlerDTO.getMethod());
                // 更新缓存中的映射信息
                REGISTER_MAPPING.put(key, new DynamicRegisterMappingHandlerDTO(requestMappingInfo, dynamicRegisterMappingHandlerDTO.getObject(), dynamicRegisterMappingHandlerDTO.getMethod()));
            } else {
                log.warn("尝试更新不存在的动态登录入口映射，key: {}", key);
            }
        }
    }

    /**
     * 构建登录请求映射信息对象
     * <p>
     * 根据安全登录入口路径构建 RequestMappingInfo，默认使用 POST 方法，
     * 请求和响应均为 JSON 格式。将系统配置的相对路径转换为标准的 API 路径。
     * </p>
     *
     * @param secureLoginEntry 安全登录入口路径（如 "login" 或 "my-secret-entry"）
     * @return 包含完整路径、HTTP 方法和媒体类型的 RequestMappingInfo 对象
     */
    public RequestMappingInfo buildLoginRequestMappingInfo(String secureLoginEntry) {
        // 将配置的入口路径解析为完整的 API 路径（如 /api/login）
        String loginPath = LoginEntryPathUtils.resolveLoginApiPath(secureLoginEntry);
        // 构建请求映射信息：设置路径、POST 方法、JSON 请求/响应
        return RequestMappingInfo.paths(loginPath)
                .methods(RequestMethod.POST)
                .consumes(MediaType.APPLICATION_JSON_VALUE)
                .produces(MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

}
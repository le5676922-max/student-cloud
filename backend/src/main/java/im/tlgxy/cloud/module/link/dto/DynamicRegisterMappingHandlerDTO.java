package im.tlgxy.cloud.module.link.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;

/**
 * 动态注册请求映射处理器 DTO
 * <p>
 * 封装动态注册 Spring MVC 请求映射所需的三个核心要素：
 * <ul>
 *   <li>请求映射信息（{@link RequestMappingInfo}）：定义了 URL 模式、HTTP 方法等映射规则</li>
 *   <li>控制器对象（Object）：处理请求的 Controller 实例</li>
 *   <li>处理方法（Method）：Controller 中具体处理请求的方法</li>
 * </ul>
 * <p>
 * 该类用于 {@link im.tlgxy.cloud.module.link.service.DynamicDirectLinkPrefixService} 服务中，
 * 支持在运行时动态注册、修改和注销 URL 映射，实现直链前缀的可配置化。
 * <p>
 * 使用 Lombok 注解自动生成 getter/setter（{@link Data}）和全参构造函数（{@link AllArgsConstructor}）。
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
public class DynamicRegisterMappingHandlerDTO {

    /**
     * Spring MVC 请求映射信息，包含 URL 模式、请求方法、请求参数等映射条件
     */
    private RequestMappingInfo requestMappingInfo;

    /**
     * 处理请求的控制器对象实例
     */
    private Object object;

    /**
     * 控制器中处理具体请求的方法（通过反射获取）
     */
    private Method method;

}
package im.tlgxy.cloud.core.aspect;

import im.tlgxy.cloud.core.constant.MdcConstant;
import im.tlgxy.cloud.core.util.AjaxJson;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Controller 统一返回结果处理切面。
 * <p>
 * 实现了 {@link ResponseBodyAdvice} 接口，在 Controller 方法返回结果写入响应体之前
 * 对返回值进行统一处理：当返回值是 {@link AjaxJson} 类型时，自动将 MDC 中的 TraceId
 * 设置到返回结果中，方便前端根据 TraceId 进行日志追踪和问题排查。
 * </p>
 * <p>
 * 在微服务架构中，TraceId 是全链路追踪的关键标识，将其返回给前端有助于定位问题。
 * </p>
 *
 * @author zhaojun
 */
// 声明为全局 Controller 通知，对所有 Controller 生效
@ControllerAdvice
public class CommonResultControllerAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 判断当前返回值是否需要由本类进行处理。
     * 只有当消息转换器是 Jackson 类型时（即返回 JSON 格式）才进行处理。
     *
     * @param returnType    返回值类型
     * @param converterType 消息转换器类型
     * @return true 表示需要处理，false 表示跳过
     */
    @Override
    public boolean supports(MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        // 只有在使用 Jackson 进行 JSON 序列化时才介入处理
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    /**
     * 在将响应体写入响应流之前执行，对返回值进行包装处理。
     *
     * @param body          原始返回值（Controller 方法的返回对象）
     * @param returnType    返回值类型元数据
     * @param contentType   响应的 Content-Type
     * @param converterType 消息转换器类型
     * @param request       HTTP 请求对象
     * @param response      HTTP 响应对象
     * @return 处理后的返回值对象
     */
    @Override
    @NonNull
    public final Object beforeBodyWrite(@Nullable Object body,
                                        @NonNull MethodParameter returnType,
                                        @NonNull MediaType contentType,
                                        @NonNull Class<? extends HttpMessageConverter<?>> converterType,
                                        @NonNull ServerHttpRequest request,
                                        @NonNull ServerHttpResponse response) {
        // 将返回值包装或转换为 MappingJacksonValue 容器
        MappingJacksonValue container = getOrCreateContainer(body);
        // 在写入之前进行内部处理（如设置 TraceId）
        beforeBodyWriteInternal(container, contentType, returnType, request, response);
        return container;
    }

    /**
     * 将返回值包装在 {@link MappingJacksonValue} 容器中（用于提供额外的序列化指令）。
     * 如果已经是该类型，则直接强转返回。
     *
     * @param body 原始返回值
     * @return 包装后的 MappingJacksonValue 对象
     */
    private MappingJacksonValue getOrCreateContainer(Object body) {
        return body instanceof MappingJacksonValue ? (MappingJacksonValue) body :
                new MappingJacksonValue(body);
    }

    /**
     * 内部处理逻辑：如果返回值是 {@link AjaxJson} 类型，则将 MDC 中的 TraceId 注入到返回值中。
     *
     * @param bodyContainer MappingJacksonValue 值容器
     * @param contentType   响应内容类型
     * @param returnType    返回值类型
     * @param request       HTTP 请求
     * @param response      HTTP 响应
     */
    private void beforeBodyWriteInternal(MappingJacksonValue bodyContainer,
                                         MediaType contentType,
                                         MethodParameter returnType,
                                         ServerHttpRequest request,
                                         ServerHttpResponse response) {
        // 获取实际的返回值对象
        Object returnBody = bodyContainer.getValue();

        // 如果返回值是 AjaxJson 及其子类，则将 MDC 中的 TraceId 设置到返回值中
        if (returnBody instanceof AjaxJson<?> baseResponse) {
            // 将 MDC 中的 TraceId 设置到返回值中，方便前端进行日志追踪
            baseResponse.setTraceId(MDC.get(MdcConstant.TRACE_ID));
        }
    }

}

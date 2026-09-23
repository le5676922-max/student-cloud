package im.tlgxy.cloud.core.constant;

/**
 * SLF4J MDC（Mapped Diagnostic Context，映射诊断上下文）常量类。
 * <p>
 * 定义了在日志追踪中使用的 MDC key 常量，用于在日志中关联和追踪
 * 同一请求链路的上下文信息（如 TraceId、IP、用户等）。
 * </p>
 * <p>
 * 使用方式：<br>
 * <pre>{@code
 * MDC.put(MdcConstant.TRACE_ID, traceId);
 * // ... 执行业务逻辑 ...
 * MDC.remove(MdcConstant.TRACE_ID);
 * }</pre>
 * </p>
 * <p>
 * 在 logback/log4j2 的日志模式中可以通过 %X{traceId} 等方式引用这些上下文变量。
 * </p>
 *
 * @author zhaojun
 */
public class MdcConstant {

    /**
     * 链路追踪 ID，用于在分布式系统中追踪完整的请求链路。
     * 通常在请求到达时生成（或从上游系统传入），随请求贯穿整个调用链。
     */
    public static final String TRACE_ID = "traceId";

    /**
     * 请求来源的客户端 IP 地址。
     * 用于日志中记录请求来源，便于安全审计和问题定位。
     */
    public static final String IP = "ip";

    /**
     * 当前操作用户的标识。
     * 用于日志中记录操作人信息，便于审计和追踪。
     */
    public static final String USER = "user";

}

package im.tlgxy.cloud.core.util;

import im.tlgxy.cloud.core.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * Ajax JSON 响应封装类
 * <p>
 * 用于统一封装所有 Ajax 请求返回的 JSON 格式数据。
 * 该类提供了多种静态工厂方法，方便快速构建成功、失败、分页等常见响应。
 * 所有响应都包含业务状态码（code）、响应消息（msg）和可选的响应数据（data）。
 * </p>
 * <p>
 * 状态码规则：
 * <ul>
 *   <li>{@code "0"} — 表示请求成功</li>
 *   <li>其他值 — 表示异常，具体错误信息见响应消息</li>
 * </ul>
 * </p>
 *
 * @param <T> 响应数据的类型
 * @author zhaojun
 */
@Data
@ToString
public class AjaxJson<T> implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 成功状态码常量 */
    public static final String CODE_SUCCESS = "0";

    /** 业务状态码，0 为正常，其他值均为异常 */
    @Schema(title = "业务状态码，0 为正常，其他值均为异常，异常情况下见响应消息", example = "0")
    private final String code;

    /** 响应消息 */
    @Schema(title = "响应消息", example = "ok")
    private String msg;

    /** 响应数据 */
    @Schema(title = "响应数据")
    private T data;

    /** 数据总条数，仅在分页查询时有效 */
    @Schema(title = "数据总条数，分页情况有效")
    private final Long dataCount;

    /** 请求跟踪 ID，用于链路追踪 */
    @Schema(title = "跟踪 ID")
    private String traceId;

    /**
     * 构造方法 — 指定状态码和消息，不带数据和分页信息
     *
     * @param code 业务状态码，为 {@code null} 时默认使用系统错误码
     * @param msg  响应消息
     */
    public AjaxJson(String code, String msg) {
        if (code == null) {
            code = ErrorCode.SYSTEM_ERROR.getCode();
        }
        this.code = code;
        this.msg = msg;
        this.dataCount = null;
    }

    /**
     * 构造方法 — 指定状态码、消息和数据，不带分页信息
     *
     * @param code 业务状态码
     * @param msg  响应消息
     * @param data 响应数据
     */
    public AjaxJson(String code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.dataCount = null;
    }

    /**
     * 构造方法 — 指定状态码、消息、数据和分页总数
     *
     * @param code      业务状态码
     * @param msg       响应消息
     * @param data      响应数据
     * @param dataCount 数据总条数
     */
    public AjaxJson(String code, String msg, T data, Long dataCount) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.dataCount = dataCount;
    }

    /**
     * 返回一个不带数据的成功响应（默认消息 "ok"）
     *
     * @return 成功的 AjaxJson 实例
     */
    public static AjaxJson<Void> getSuccess() {
        return new AjaxJson<>(CODE_SUCCESS, "ok");
    }

    /**
     * 返回一个不带数据的成功响应，使用自定义消息
     *
     * @param msg 自定义成功消息
     * @return 成功的 AjaxJson 实例
     */
    public static AjaxJson<Void> getSuccess(String msg) {
        return new AjaxJson<>(CODE_SUCCESS, msg);
    }

    /**
     * 返回一个带数据的成功响应
     *
     * @param msg  成功消息
     * @param data 响应数据
     * @param <T>  响应数据的类型
     * @return 成功的 AjaxJson 实例
     */
    public static <T> AjaxJson<T> getSuccess(String msg, T data) {
        return new AjaxJson<>(CODE_SUCCESS, msg, data);
    }

    /**
     * 返回一个带数据的成功响应（默认消息 "ok"）
     *
     * @param data 响应数据
     * @param <T>  响应数据的类型
     * @return 成功的 AjaxJson 实例
     */
    public static <T> AjaxJson<T> getSuccessData(T data) {
        return new AjaxJson<>(CODE_SUCCESS, "ok", data);
    }

    /**
     * 返回一个带分页信息的成功响应
     *
     * @param dataCount 数据总条数
     * @param data      当前页数据
     * @param <T>       响应数据的类型
     * @return 成功的 AjaxJson 实例，包含分页信息
     */
    public static <T> AjaxJson<T> getPageData(Long dataCount, T data) {
        return new AjaxJson<>(CODE_SUCCESS, "ok", data, dataCount);
    }

    /**
     * 返回一个系统错误响应
     *
     * @param msg 错误消息
     * @return 错误的 AjaxJson 实例
     */
    public static AjaxJson<Void> getError(String msg) {
        return new AjaxJson<>(ErrorCode.SYSTEM_ERROR.getCode(), msg);
    }

    /**
     * 返回一个未登录的错误响应
     *
     * @return 未登录的 AjaxJson 实例
     */
    public static AjaxJson<?> getUnauthorizedResult() {
        return new AjaxJson<>(ErrorCode.BIZ_UNAUTHORIZED.getCode(), "未登录，请登录后再次访问");
    }

    /**
     * 返回一个未授权（无权限）的错误响应
     *
     * @return 未授权的 AjaxJson 实例
     */
    public static AjaxJson<?> getForbiddenResult() {
        return new AjaxJson<>(ErrorCode.NO_FORBIDDEN.getCode(), "未授权，请登录正确权限账号再试");
    }

    /**
     * 返回一个资源未找到的错误响应
     *
     * @return 资源未找到的 AjaxJson 实例
     */
    public static AjaxJson<?> getNotFoundResult() {
        return new AjaxJson<>(ErrorCode.BIZ_NOT_FOUND.getCode(), ErrorCode.BIZ_NOT_FOUND.getMessage());
    }

    /**
     * 返回一个自定义错误码和消息的错误响应
     *
     * @param code 自定义业务错误码
     * @param msg  错误消息
     * @return 错误的 AjaxJson 实例
     */
    public static AjaxJson<?> getError(String code, String msg) {
        return new AjaxJson<>(code, msg);
    }

}

package io.studentcloud.common;

/** 定义前后端统一响应结构，承载业务状态、提示与数据。
 *
 * External response contract, independently implemented for the existing browser client.
 */
public record ApiResponse<T>(String code, String msg, T data, Long dataCount, String traceId) {
    public static <T> ApiResponse<T> success(T value) {
        return new ApiResponse<>("0", "ok", value, null, null);
    }

    public static ApiResponse<Void> failure(String code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, null, traceId);
    }
}

package io.studentcloud.common;

/** External response contract, independently implemented for the existing browser client. */
public record ApiResponse<T>(String code, String msg, T data, Long dataCount, String traceId) {
    public static <T> ApiResponse<T> success(T value) {
        return new ApiResponse<>("0", "ok", value, null, null);
    }

    public static ApiResponse<Void> failure(String code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, null, traceId);
    }
}

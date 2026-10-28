package io.studentcloud.runtime.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import java.util.UUID;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class HttpFailures {
    private static final Logger LOG = LoggerFactory.getLogger(HttpFailures.class);

    @ExceptionHandler(ApplicationFault.class)
    ResponseEntity<ApiResponse<Void>> application(ApplicationFault failure) {
        var response=ResponseEntity.status(failure.httpStatus());
        if(failure.httpStatus()==429) response.header("Retry-After","60");
        return response.body(ApiResponse.failure(failure.code(), failure.getMessage(), UUID.randomUUID().toString()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiResponse<Void>> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("40000", "请检查请求字段", UUID.randomUUID().toString()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> oversized(MaxUploadSizeExceededException ignored) {
        return ResponseEntity.status(413).body(ApiResponse.failure("UPLOAD_TOO_LARGE", "单个文件不能超过50MiB，请缩小后重新上传", UUID.randomUUID().toString()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception failure) {
        String trace = UUID.randomUUID().toString();
        // Avoid serializing SQL, tokens, database URLs, local paths, or supplier responses.
        LOG.error("Unhandled independent API failure: trace={}, type={}", trace, failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ApiResponse.failure("50000", "请求未完成，请稍后重试", trace));
    }
}

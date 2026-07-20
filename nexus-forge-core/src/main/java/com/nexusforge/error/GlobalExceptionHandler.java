package com.nexusforge.error;

import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器，捕获所有未处理的异常并返回统一的错误响应
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. 业务异常
    @ExceptionHandler(value = BusinessException.class)
    public ResponseEntity<Result<?>> handleBusinessException(BusinessException ex) {
        log.error("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        HttpStatus status = mapStatus(ex.getCode());
        return ResponseEntity.status(status).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    // 2. JSR-303校验异常(@Valid)
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleException(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(";"));
        log.error("参数校验异常: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ResultCode.VALIDATION_FAILED, msg));
    }

    // 3. @ModelAttribute 绑定异常
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.VALIDATION_FAILED.getCode(), msg));
    }

    // 4. 404 路由不存在
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handle404(NoHandlerFoundException ex) {
        log.warn("404: {}", ex.getRequestURL());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "请求路径不存在: " + ex.getRequestURL()));
    }

    /**
     * 处理文件上传超限异常
     *
     * @param ex MaxUploadSizeExceededException
     * @return ResponseEntity<Result<Void>>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        long max = ex.getMaxUploadSize();
        String msg = "文件大小超过限制" + (max > 0 ? "（最大 " + (max / 1024 / 1024) + "MB）" : "");
        log.warn("文件上传超限: maxSize={} bytes", max);
        return ResponseEntity
                .status(HttpStatus.CONTENT_TOO_LARGE)   // 413
                .body(Result.fail(ResultCode.FILE_TOO_LARGE.getCode(), msg));
    }

    // 5. 兜底异常（必须放最后）
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAny(Exception ex) {
        log.error("系统异常", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.INTERNAL_ERROR));
    }

    private static HttpStatus mapStatus(Integer code) {
        if (code == null) return HttpStatus.BAD_REQUEST;
        return switch (code) {
            case 2008 -> HttpStatus.CONFLICT;          // IDEMPOTENT_CONFLICT          -> 409
            case 2009 -> HttpStatus.TOO_MANY_REQUESTS; // RATE_LIMITED                 -> 429
            case 3001,                              // LLM_CONFIG_MISSING
                 3002,                              // LLM_MODEL_NOT_FOUND
                 3003 -> HttpStatus.BAD_REQUEST;    // LLM_INVALID_REQUEST          -> 400
            case 3006,                              // LLM_RATE_LIMITED
                 3007 -> HttpStatus.TOO_MANY_REQUESTS; // LLM_QUOTA_EXCEEDED         -> 429
            case 3004 -> HttpStatus.BAD_GATEWAY;       // LLM_PROVIDER_ERROR          -> 502
            case 3005 -> HttpStatus.GATEWAY_TIMEOUT;   // LLM_UPSTREAM_TIMEOUT        -> 504
            default -> HttpStatus.BAD_REQUEST;       // 其余业务异常                 -> 400
        };
    }
}

package com.example.interview.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 全局异常处理器。
 *
 * <p>职责说明：</p>
 * <p>1. 为常见业务异常和框架异常返回统一的 JSON 响应结构。</p>
 * <p>2. 避免将服务端堆栈或内部实现细节直接暴露给前端或 IM 调用方。</p>
 * <p>3. 为测试和运维提供稳定、可预期的异常响应语义。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理上传文件超出大小限制的异常。
     *
     * @param ignored Spring MVC 抛出的上传大小异常
     * @return 统一的 413 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ignored,
                                                         HttpServletRequest request) {
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("上传文件超过服务端限制，请上传 20MB 以内 PDF");
        }
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "上传文件超过服务端限制，请上传 20MB 以内 PDF"));
    }

    /**
     * 处理参数非法类异常。
     *
     * @param exception 非法参数异常
     * @return 统一的 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException exception,
                                                   HttpServletRequest request) {
        String message = exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? "请求参数不合法"
                : exception.getMessage();
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(message);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message));
    }

    /**
     * 处理未被更细粒度异常处理器接住的通用异常。
     *
     * @param exception 通用运行时异常
     * @return 统一的 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception exception, HttpServletRequest request) {
        if (isSseRequest(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("服务器内部错误，请稍后重试");
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器内部错误，请稍后重试"));
    }

    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        return (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE))
                || (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE));
    }
}

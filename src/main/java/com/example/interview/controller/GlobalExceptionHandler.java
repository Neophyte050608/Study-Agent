package com.example.interview.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 全局异常处理器（GlobalExceptionHandler）。
 * 
 * 该类通过 Spring MVC 的 @RestControllerAdvice 机制，集中拦截并处理整个系统中抛出的异常。
 * 核心目标：
 * 1. 统一异常响应格式，避免直接暴露后端堆栈信息。
 * 2. 将框架级异常映射为用户更易理解的中文提示和合适的 HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理上传文件超过大小限制的异常。
     * 
     * @param ignored 捕获到的异常实例
     * @return 包含友好提示的 413 Payload Too Large 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ignored) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "上传文件超过服务端限制，请上传 10MB 以内 PDF"));
    }
}

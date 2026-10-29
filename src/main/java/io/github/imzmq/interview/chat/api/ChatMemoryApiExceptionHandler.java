package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryManagementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ChatMemoryController.class)
public class ChatMemoryApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryApiExceptionHandler.class);

    @ExceptionHandler(ChatMemoryManagementException.class)
    public ResponseEntity<Map<String, String>> handleMemoryManagement(
            ChatMemoryManagementException exception) {
        return switch (exception.code()) {
            case "MEMORY_STATUS_INVALID" -> response(
                    HttpStatus.BAD_REQUEST, exception.code(), "记忆状态不合法");
            case "MEMORY_NOT_FOUND" -> response(
                    HttpStatus.NOT_FOUND, exception.code(), "记忆不存在");
            case "MEMORY_STATE_CONFLICT" -> response(
                    HttpStatus.CONFLICT, exception.code(), "记忆状态已变化");
            case "MEMORY_CARDINALITY_CONFLICT" -> response(
                    HttpStatus.CONFLICT, exception.code(), "记忆基数不允许共存");
            default -> response(
                    HttpStatus.INTERNAL_SERVER_ERROR, "MEMORY_INTERNAL_ERROR", "服务器内部错误");
        };
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleInvalidInput(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "MEMORY_REQUEST_INVALID", "请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        log.warn("Chat memory request failed with unexpected exception type: {}",
                exception.getClass().getSimpleName());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "MEMORY_INTERNAL_ERROR", "服务器内部错误");
    }

    private ResponseEntity<Map<String, String>> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}

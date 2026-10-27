package io.github.imzmq.interview.learning.api;

import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

import static io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException.Code.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = LearningPlanPreviewController.class)
public class LearningPlanApiExceptionHandler {
    @ExceptionHandler(LearningPlanAccessException.class)
    public ResponseEntity<Map<String, String>> access(LearningPlanAccessException exception) {
        var code = exception.code();
        HttpStatus status = switch (code) {
            case INVALID_INPUT, INVALID_PREVIEW_TOPIC, INVALID_PREVIEW_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case EXECUTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, REVISION_CONFLICT, EXECUTION_NOT_RETRYABLE, EXECUTION_NOT_TERMINAL,
                 RETRY_ROOT_NOT_FAILED, ATTEMPT_BUDGET_EXHAUSTED -> HttpStatus.CONFLICT;
            case EXECUTION_DISABLED, EXECUTION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_EXECUTION_RESULT, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String message = switch (status) {
            case BAD_REQUEST -> "请求参数不合法";
            case UNAUTHORIZED -> "需要有效的认证身份";
            case NOT_FOUND -> "任务不存在";
            case CONFLICT -> "任务状态或请求冲突";
            case SERVICE_UNAVAILABLE -> "持久任务暂不可用";
            default -> "服务器内部错误";
        };
        return ResponseEntity.status(status).body(Map.of("code", code.name(), "message", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> input(Exception ignored) {
        return access(new LearningPlanAccessException(INVALID_INPUT));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception ignored) {
        return access(new LearningPlanAccessException(INTERNAL_ERROR));
    }
}

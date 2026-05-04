package io.github.imzmq.interview.common.api;

import java.time.Instant;

/**
 * 统一 API 响应体。
 *
 * @param code      0=成功, 非0=错误码
 * @param message   可读消息
 * @param data      业务数据
 * @param timestamp ISO 8601 时间戳
 * @param path      请求路径
 */
public record ApiResponse<T>(
    int code,
    String message,
    T data,
    String timestamp,
    String path
) {
    public static <T> ApiResponse<T> ok(T data, String path) {
        return new ApiResponse<>(0, "ok", data, Instant.now().toString(), path != null ? path : "");
    }

    public static <T> ApiResponse<T> error(int code, String message, String path) {
        return new ApiResponse<>(code, message, null, Instant.now().toString(), path != null ? path : "");
    }
}

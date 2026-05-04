package io.github.imzmq.interview.chat.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM JSON 解析统一返回封装。
 *
 * @param <T>       解析后的数据类型
 * @param success   是否成功
 * @param data      成功时的解析结果
 * @param failureReason 失败原因（可直接写入日志）
 * @param attempts  LLM 实际调用次数（含重试）
 * @param warnings  非致命警告（如类型强转记录）
 */
public record JsonResult<T>(
        boolean success,
        T data,
        String failureReason,
        int attempts,
        List<String> warnings) {

    public static <T> JsonResult<T> success(T data, int attempts, List<String> warnings) {
        List<String> safe = warnings == null ? List.of() : List.copyOf(warnings);
        return new JsonResult<>(true, data, "", attempts, safe);
    }

    public static <T> JsonResult<T> failure(String reason, int attempts) {
        return new JsonResult<>(false, null, reason == null ? "未知错误" : reason, attempts, List.of());
    }

    /** 合并新警告到已有结果（用于分层追加警告信息）。 */
    public JsonResult<T> withWarning(String warning) {
        if (!success || warning == null || warning.isBlank()) {
            return this;
        }
        List<String> merged = new ArrayList<>(warnings);
        merged.add(warning);
        return new JsonResult<>(true, data, failureReason, attempts, Collections.unmodifiableList(merged));
    }
}

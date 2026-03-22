package com.example.interview.agent.task;

/**
 * 任务响应实体类 (TaskResponse)
 * 统一各业务 Agent 处理后的返回格式。
 * 
 * @param success 是否执行成功
 * @param message 提示消息
 * @param data    业务结果数据
 */
public record TaskResponse(
        boolean success,
        String message,
        Object data
) {
    /**
     * 快捷成功响应
     */
    public static TaskResponse ok(Object data) {
        return new TaskResponse(true, "ok", data);
    }

    /**
     * 快捷失败响应
     */
    public static TaskResponse fail(String message) {
        return new TaskResponse(false, message, null);
    }
}

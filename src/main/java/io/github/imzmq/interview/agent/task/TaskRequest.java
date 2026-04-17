package io.github.imzmq.interview.agent.task;

import java.util.Map;

/**
 * 任务请求实体类 (TaskRequest)
 * 用于封装从各渠道（如 IM、Web）进入系统的统一任务指令。
 * 
 * @param taskType 任务类型，如果为 null 则触发 ReAct 智能路由
 * @param payload  业务负载数据（如用户 query、topic 等）
 * @param context  上下文信息（如 sessionId、userId、历史对话等）
 */
public record TaskRequest(
        TaskType taskType,
        Map<String, Object> payload,
        Map<String, Object> context
) {
}


package com.example.interview.agent.a2a;

/**
 * A2A 消息意图。
 *
 * <p>DELEGATE_TASK：委派任务（通常由编排/路由侧发出）。</p>
 * <p>EXECUTE_TASK：执行任务（保留扩展点，表示“直接执行”的语义）。</p>
 * <p>RETURN_RESULT：返回结果（用于 requestReply 或 replyTo 回包）。</p>
 */
public enum A2AIntent {
    DELEGATE_TASK,
    EXECUTE_TASK,
    RETURN_RESULT
}

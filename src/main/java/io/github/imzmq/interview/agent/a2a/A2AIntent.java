package io.github.imzmq.interview.agent.a2a;

/**
 * A2A 消息意图。
 *
 * <p>DELEGATE_TASK：委派任务（通常由编排/路由侧发出）。</p>
 * <p>EXECUTE_TASK：执行任务（保留扩展点，表示“直接执行”的语义）。</p>
 * <p>RETURN_RESULT：返回结果（用于 requestReply 或 replyTo 回包）。</p>
 * <p>ROLLING_SUMMARY：触发滚动式总结任务（用于长对话的上下文压缩）。</p>
 * <p>CHAT_CONTEXT_COMPRESS：触发 Web Chat 会话上下文压缩任务。</p>
 * <p>CROSS_SESSION_MEMORIZE：触发跨会话记忆提取与合并任务。</p>
 */
public enum A2AIntent {
    DELEGATE_TASK,
    EXECUTE_TASK,
    RETURN_RESULT,
    ROLLING_SUMMARY,
    CHAT_CONTEXT_COMPRESS,
    CROSS_SESSION_MEMORIZE
}


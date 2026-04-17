package io.github.imzmq.interview.agent.a2a;

/**
 * A2A 错误信息封装（用于把失败原因随消息一起传递，而不是依赖异常栈）。
 */
public record A2AError(
        String code,
        String message
) {
}


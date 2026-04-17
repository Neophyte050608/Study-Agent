package io.github.imzmq.interview.agent.a2a;

/**
 * A2A 链路追踪信息。
 *
 * <p>traceId：同一链路的全局追踪ID。</p>
 * <p>parentMessageId：父消息ID（用于还原调用树/因果关系）。</p>
 */
public record A2ATrace(
        String traceId,
        String parentMessageId
) {
}


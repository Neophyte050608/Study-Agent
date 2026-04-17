package io.github.imzmq.interview.agent.a2a;

import java.time.Instant;
import java.util.Map;

/**
 * A2A 消息信封（跨 Agent 的统一数据结构）。
 *
 * <p>字段要点：</p>
 * <ul>
 *   <li>messageId：单条消息唯一ID（用于幂等去重）。</li>
 *   <li>correlationId：请求链路关联ID（请求/应答应保持一致，用于 requestReply 关联）。</li>
 *   <li>sender/receiver：逻辑发送方/接收方标识。</li>
 *   <li>replyTo：可选回包接收方标识（用于 RETURN_RESULT）。</li>
 *   <li>intent/status/error：意图、状态与错误信息。</li>
 *   <li>payload/context：业务载荷与上下文（例如超时/重试配置、用户信息等）。</li>
 *   <li>metadata/trace：能力/来源标签与链路追踪信息。</li>
 * </ul>
 */
public record A2AMessage(
        String protocolVersion,
        String messageId,
        String correlationId,
        String sender,
        String receiver,
        String replyTo,
        A2AIntent intent,
        Map<String, Object> payload,
        Map<String, Object> context,
        A2AStatus status,
        A2AError error,
        A2AMetadata metadata,
        A2ATrace trace,
        Instant createdAt
) {
}


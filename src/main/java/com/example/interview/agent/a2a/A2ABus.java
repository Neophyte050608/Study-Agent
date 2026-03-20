package com.example.interview.agent.a2a;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Agent-to-Agent（A2A）消息总线抽象。
 *
 * <p>设计目标：把不同智能体之间的协作通信统一成“消息信封 + 订阅/发布”，以便：</p>
 * <ul>
 *   <li>解耦：发送方只关心 receiver 标识，不依赖对方实现细节。</li>
 *   <li>观测：统一携带 correlationId/traceId/metadata，便于链路追踪与审计。</li>
 *   <li>降级：可用不同实现（内存、RocketMQ等），并在不可用时回退。</li>
 * </ul>
 *
 * <p>语义说明：</p>
 * <ul>
 *   <li>publish：best-effort 投递；具体幂等/重试由实现决定。</li>
 *   <li>subscribe：按 receiver 订阅；部分实现支持通配符 receiver（例如 "*"）。</li>
 *   <li>requestReply：可选的“请求-应答”封装，通常依赖 correlationId 关联。</li>
 * </ul>
 */
public interface A2ABus {
    void publish(A2AMessage message);

    void subscribe(String receiver, Consumer<A2AMessage> handler);

    default Optional<A2AMessage> requestReply(A2AMessage message) {
        publish(message);
        return Optional.empty();
    }
}

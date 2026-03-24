package com.example.interview.agent.a2a;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消费者监听器 (A2A Bus 接入点)。
 * 
 * 【架构思考与设计原理】
 * 1. 为什么采用 RocketMQ 而非 Spring Event / 本地线程池？
 *    - 大模型总结等任务属于 CPU/IO 密集型长耗时任务（长达 5-10 秒）。如果使用本地线程池，一旦服务重启，积压的任务会全部丢失。
 *    - RocketMQ 提供了分布式可靠投递保证，解耦了业务主流程（面试问答）与旁路流程（画像计算、上下文压缩）。
 * 2. 死信队列 (DLQ) 的作用：
 *    - 如果大模型 API 持续超时，或者消息反序列化失败，消费会被拒绝。重试达到上限后进入 DLQ。
 *    - 这使得开发人员可以后续通过 `Replay` 接口人工干预和重放，保障了学习画像数据的最终一致性。
 * 
 * 核心职责：
 * 1. 监听配置的 Topic（如 a2a-events），接收其他节点或异步组件发出的消息。
 * 2. 消息反序列化与分发：调用 RocketMqA2ABus 的 dispatchInbound 方法，将消息路由给对应的 Agent 订阅者（如 RollingSummaryAgent）。
 * 3. 死信队列 (DLQ) 降级：如果消息反序列化失败或格式不合法，将其投递到 DLQ Topic 以便后续排查和重放。
 */
@Component
@ConditionalOnProperty(name = "app.a2a.bus.type", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${app.a2a.rocketmq.topic:a2a-events}",
        consumerGroup = "${app.a2a.rocketmq.consumer-group:interview-review-a2a-consumer}"
)
public class RocketMqA2AConsumer implements RocketMQListener<String> {

    private final A2ABus a2aBus;
    private final RocketMQTemplate rocketMQTemplate;
    private final String dlqTopic;

    public RocketMqA2AConsumer(
            A2ABus a2aBus,
            ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider,
            @Value("${app.a2a.rocketmq.dlq-topic:a2a-events-dlq}") String dlqTopic
    ) {
        this.a2aBus = a2aBus;
        this.rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        this.dlqTopic = dlqTopic;
    }

    @Override
    public void onMessage(String message) {
        if (a2aBus instanceof RocketMqA2ABus rocketMqA2ABus) {
            // 将接收到的 JSON 消息派发给对应的内部 Agent（如 RollingSummaryAgent 会在这里被回调）
            boolean ok = rocketMqA2ABus.dispatchInbound(message);
            // 如果分发失败（例如 JSON 解析异常），则将原消息投递至死信队列 (DLQ)
            if (!ok && rocketMQTemplate != null && message != null && !message.isBlank()) {
                rocketMQTemplate.convertAndSend(dlqTopic, message);
            }
        }
    }
}

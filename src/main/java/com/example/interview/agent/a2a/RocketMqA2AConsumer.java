package com.example.interview.agent.a2a;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
            boolean ok = rocketMqA2ABus.dispatchInbound(message);
            if (!ok && rocketMQTemplate != null && message != null && !message.isBlank()) {
                rocketMQTemplate.convertAndSend(dlqTopic, message);
            }
        }
    }
}

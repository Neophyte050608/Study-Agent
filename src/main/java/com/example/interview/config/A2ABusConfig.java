package com.example.interview.config;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.InMemoryA2ABus;
import com.example.interview.agent.a2a.RocketMqA2ABus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * A2A (Agent-to-Agent) 消息总线装配配置类。
 * 
 * 该配置类负责根据配置文件中的属性动态选择并初始化消息总线的实现。
 * 系统支持两种类型的总线实现：
 * <ul>
 *   <li><b>inmemory</b>: 进程内内存总线，适用于单机部署或开发环境，无需外部中间件。</li>
 *   <li><b>rocketmq</b>: 基于 RocketMQ 的分布式总线，适用于生产环境或需要跨服务通信的场景。</li>
 * </ul>
 * 
 * 配置项：app.a2a.bus.type (可选值: inmemory, rocketmq)
 */
@Configuration
public class A2ABusConfig {

    @org.springframework.context.annotation.Bean
    public com.example.interview.agent.a2a.InMemoryA2ABus inMemoryA2ABus(com.example.interview.agent.a2a.A2AIdempotencyStore idempotencyStore) {
        return new com.example.interview.agent.a2a.InMemoryA2ABus(idempotencyStore);
    }

    /**
     * 定义 A2ABus Bean。
     * 
     * @param inMemoryA2ABus 默认的内存总线实现
     * @param objectMapper 用于消息序列化的 Jackson 对象
     * @param rocketMQTemplateProvider RocketMQ 模板提供者，用于按需获取实例
     * @param topic RocketMQ 消息主题
     * @param publishRetries 发布失败时的重试次数
     * @param retryBackoffMs 重试退避时间（毫秒）
     * @param busType 从配置文件读取的总线类型标识
     * @return 最终选定的 A2ABus 实现实例
     */
    @Bean
    public A2ABus a2aBus(
            InMemoryA2ABus inMemoryA2ABus,
            ObjectMapper objectMapper,
            ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider,
            @Value("${app.a2a.rocketmq.topic:a2a-events}") String topic,
            @Value("${app.a2a.rocketmq.publish-retries:2}") int publishRetries,
            @Value("${app.a2a.rocketmq.retry-backoff-ms:120}") long retryBackoffMs,
            @Value("${app.a2a.bus.type:inmemory}") String busType
    ) {
        // 归一化总线类型名称
        String normalized = busType == null ? "inmemory" : busType.trim().toLowerCase(Locale.ROOT);
        
        // 如果配置为使用 RocketMQ
        if ("rocketmq".equals(normalized)) {
            // RocketMQTemplate 可能在未引入 rocketmq 依赖或未配置 nameserver 时不可用
            RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
            // 返回包装了 RocketMQ 逻辑的实现，内部通常包含自动降级回内存总线的逻辑
            return new RocketMqA2ABus(inMemoryA2ABus, rocketMQTemplate, objectMapper, topic, publishRetries, retryBackoffMs);
        }
        
        // 默认返回内存总线实现
        return inMemoryA2ABus;
    }
}

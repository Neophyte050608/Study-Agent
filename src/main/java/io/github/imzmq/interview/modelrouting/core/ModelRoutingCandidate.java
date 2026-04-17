package io.github.imzmq.interview.modelrouting.core;

/**
 * 模型路由候选者记录。
 * 封装了从配置中解析出的单个模型定义，并由选择器 (ModelSelector) 排序后提供给执行器 (ModelRoutingExecutor)。
 *
 * @param name 候选模型的唯一业务标识名
 * @param provider 提供商标识 (如 openai, dashscope 等)，用于匹配 ChatModel 工厂
 * @param model 具体的底层模型版本号 (如 gpt-4o, deepseek-chat)
 * @param beanName Spring 容器中特定的 Bean 名称（若为空则使用默认工厂创建）
 * @param priority 优先级（数值越小优先级越高，决定了熔断降级时的顺位）
 * @param supportsThinking 是否支持深度推理 (用于过滤 ModelRouteType.THINKING 的请求)
 * @param baseUrl 动态模型的 OpenAI 兼容 API 地址
 * @param apiKeyRef 加密后的 API Key 引用
 * @param routeType 候选声明的路由范围（GENERAL / THINKING / RETRIEVAL / ALL，空表示兼容旧配置）
 * @param source 候选来源标识（YAML / DATABASE / TEST）
 */
public record ModelRoutingCandidate(
        String name,
        String provider,
        String model,
        String beanName,
        int priority,
        boolean supportsThinking,
        String baseUrl,
        String apiKeyRef,
        String routeType,
        String source
) {
}


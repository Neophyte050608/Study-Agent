package io.github.imzmq.interview.tool.gateway;

/**
 * 通用工具网关抽象。
 *
 * <p>用于把“外部工具/能力”的输入输出标准化，方便在 Agent 中作为可插拔组件注入。</p>
 */
public interface ToolGateway<I, O> {
    O run(I input);
}


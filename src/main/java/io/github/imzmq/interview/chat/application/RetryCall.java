package io.github.imzmq.interview.chat.application;

/**
 * LLM 重试回调——调用方注入自己的 LLM 调用逻辑。
 * parser 在 JSON 解析失败时调用此接口，携带修复提示。
 */
@FunctionalInterface
public interface RetryCall {

    /**
     * @param repairHint 包含上次失败原因和修正要求，直接拼接到新 prompt 尾部
     * @param attempt    当前重试次数（1-based，首次重试为 1）
     * @return LLM 新的原始响应文本
     * @throws Exception 调用方 LLM 调用失败时抛出
     */
    String retry(String repairHint, int attempt) throws Exception;
}

package com.example.interview.agent.a2a;

import java.util.Map;

/**
 * A2A 消息元信息（用于分桶、观测与幂等键构造）。
 *
 * <p>capability：本次消息所属能力域（例如 task-routing、rag、evaluation）。</p>
 * <p>source：消息来源组件（用于定位是谁发出的）。</p>
 * <p>tags：额外标签（例如 taskType 等），用于日志筛选与统计。</p>
 */
public record A2AMetadata(
        String capability,
        String source,
        Map<String, Object> tags
) {
}

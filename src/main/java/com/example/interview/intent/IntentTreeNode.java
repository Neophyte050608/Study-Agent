package com.example.interview.intent;

import java.io.Serializable;
import java.util.List;

/**
 * 意图树节点。
 * 用于定义面试系统中的意图识别分类树。
 */
public record IntentTreeNode(
        String intentId,
        String path,
        String name,
        String description,
        String taskType,
        List<String> examples,
        List<String> slotHints
) implements Serializable {
}

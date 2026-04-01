package com.example.interview.intent;

import java.io.Serializable;
import java.util.List;

/**
 * 意图树节点（Intent Tree Node）。
 *
 * <p>该 record 用于描述系统中的“意图分类树”节点定义，可作为配置/数据库实体的 API 层模型：</p>
 * <ul>
 *     <li>非叶子节点：用于组织层级结构（path/name/description），taskType 通常为空</li>
 *     <li>叶子节点：代表一个可执行的业务意图，taskType 不为空，并配套 examples/slotHints 以提升模型命中率</li>
 * </ul>
 *
 * <p>字段说明：</p>
 * <ul>
 *     <li>intentId：节点唯一标识（数据库主键或配置 ID）</li>
 *     <li>path：树路径（如 CODING.PRACTICE.ALGORITHM），用于稳定引用与前端展示</li>
 *     <li>examples：该意图的示例问法（用于 few-shot 或相似度提示）</li>
 *     <li>slotHints：槽位提示（告诉模型在该意图下需要抽取哪些结构化字段）</li>
 * </ul>
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

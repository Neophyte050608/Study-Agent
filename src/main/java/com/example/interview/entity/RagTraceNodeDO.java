package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG Trace 节点实体。
 *
 * <p>职责说明：</p>
 * <p>1. 持久化单个节点的输入、输出、错误与结构化指标。</p>
 * <p>2. 为链路详情页提供节点级回放数据。</p>
 * <p>3. 与 `RagTraceDO` 通过 `traceId` 关联，支撑父子节点树还原。</p>
 */
@Data
@TableName("t_rag_trace_node")
public class RagTraceNodeDO {

    /**
     * 数据库自增主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属 Trace ID。
     */
    private String traceId;

    /**
     * 节点唯一 ID。
     */
    private String nodeId;

    /**
     * 父节点 ID。
     */
    private String parentNodeId;

    /**
     * 节点类型，例如 ROOT / RETRIEVAL / GENERATION。
     */
    private String nodeType;

    /**
     * 节点展示名称。
     */
    private String nodeName;

    /**
     * 节点状态，例如 RUNNING / COMPLETED / FAILED。
     */
    private String nodeStatus;

    /**
     * 节点输入摘要。
     */
    private String inputSummary;

    /**
     * 节点输出摘要。
     */
    private String outputSummary;

    /**
     * 节点错误摘要。
     */
    private String errorSummary;

    /**
     * 节点结构化指标中的召回文档数。
     */
    private Integer retrievedDocs;

    /**
     * 节点是否触发了 Web fallback。
     */
    private Boolean webFallbackUsed;

    /**
     * 节点开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 节点结束时间。
     */
    private LocalDateTime endedAt;

    /**
     * 节点耗时，单位毫秒。
     */
    private Long durationMs;

    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     */
    private LocalDateTime updatedAt;
}

package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG Trace 汇总实体。
 *
 * <p>职责说明：</p>
 * <p>1. 对应一条完整链路的汇总记录，用于最近历史列表与概览统计。</p>
 * <p>2. 保存根节点信息、整体状态、耗时以及检索指标聚合结果。</p>
 * <p>3. 与 `RagTraceNodeDO` 形成一对多关系，支持后续详情回放。</p>
 */
@Data
@TableName("t_rag_trace")
public class RagTraceDO {

    /**
     * 数据库自增主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 链路唯一追踪 ID。
     */
    private String traceId;

    /**
     * 根节点 ID。
     */
    private String rootNodeId;

    /**
     * 根节点类型。
     */
    private String rootNodeType;

    /**
     * 根节点名称。
     */
    private String rootNodeName;

    /**
     * Trace 总体状态，例如 COMPLETED / FAILED。
     */
    private String traceStatus;

    /**
     * 链路节点总数。
     */
    private Integer nodeCount;

    /**
     * 检索节点总数。
     */
    private Integer retrievalNodeCount;

    /**
     * 检索节点召回文档数之和。
     */
    private Integer totalRetrievedDocs;

    /**
     * 单条链路中最大的召回文档数。
     */
    private Integer maxRetrievedDocs;

    /**
     * 是否至少发生过一次 Web fallback。
     */
    private Boolean webFallbackUsed;

    /**
     * Trace 开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * Trace 结束时间。
     */
    private LocalDateTime endedAt;

    /**
     * Trace 总耗时，单位毫秒。
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



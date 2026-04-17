package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索评测单样本结果实体。
 *
 * <p>职责说明：</p>
 * <p>1. 对应 `t_retrieval_eval_case` 表，持久化单次评测运行中每个 query 的命中结果。</p>
 * <p>2. 支撑评测详情页、失败样本回放与 A/B 对比中的逐样本差异分析。</p>
 */
@Data
@TableName(value = "t_retrieval_eval_case", autoResultMap = true)
public class RetrievalEvalCaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属运行 ID。
     */
    private String runId;

    /**
     * 样本序号，保持与运行时遍历顺序一致，便于前端稳定展示。
     */
    private Integer caseIndex;

    /**
     * 原始查询文本。
     */
    private String query;

    /**
     * 预期关键词列表，使用 JSON 存储便于后续直接回显。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> expectedKeywords;

    /**
     * 数据集标签，例如 default/manual/csv。
     */
    private String tag;

    /**
     * 是否命中。
     */
    private Boolean hit;

    /**
     * 首次命中的排名，未命中时为 -1。
     */
    private Integer rank;

    /**
     * 召回片段列表，保留前 5 条用于回放分析。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> retrievedSnippets;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}



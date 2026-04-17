package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 检索评测运行汇总实体。
 *
 * <p>职责说明：</p>
 * <p>1. 对应 `t_retrieval_eval_run` 表，持久化单次评测运行的总体指标。</p>
 * <p>2. 为评测历史列表、详情页头部摘要与 A/B 对比提供稳定的数据来源。</p>
 */
@Data
@TableName(value = "t_retrieval_eval_run", autoResultMap = true)
public class RetrievalEvalRunDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 评测运行唯一标识，供详情查询与 A/B 对比使用。
     */
    private String runId;

    /**
     * 评测数据集来源，例如 default/manual/csv。
     */
    private String datasetSource;

    /**
     * 用于展示的评测标签，便于列表快速识别本次运行用途。
     */
    private String runLabel;

    /**
     * 评测样本总数。
     */
    private Integer totalCases;

    /**
     * Recall@5 命中的样本数。
     */
    private Integer hitCases;

    /**
     * Recall@1 指标。
     */
    private Double recallAt1;

    /**
     * Recall@3 指标。
     */
    private Double recallAt3;

    /**
     * Recall@5 指标。
     */
    private Double recallAt5;

    /**
     * MRR 指标。
     */
    private Double mrr;

    /**
     * 本次评测的运行时间戳字符串，保留原始展示口径。
     */
    private String reportTimestamp;

    /**
     * 实验标签，用于区分不同参数组、分支或优化轮次。
     */
    private String experimentTag;

    /**
     * 参数快照，记录本次实验使用的关键参数，便于后续回溯。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameterSnapshot;

    /**
     * 运行备注，用于补充本次实验目的、假设或观察说明。
     */
    private String notes;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}



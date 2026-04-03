package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG 生成质量评测运行汇总实体。
 */
@Data
@TableName(value = "t_rag_quality_eval_run", autoResultMap = true)
public class RagQualityEvalRunDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String datasetSource;

    private String runLabel;

    private String experimentTag;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameterSnapshot;

    private String notes;

    private Integer totalCases;

    private Double avgFaithfulness;

    private Double avgAnswerRelevancy;

    private Double avgContextPrecision;

    private Double avgContextRecall;

    private String reportTimestamp;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

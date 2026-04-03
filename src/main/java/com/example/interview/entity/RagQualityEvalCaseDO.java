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
 * RAG 生成质量评测单样本结果实体。
 */
@Data
@TableName(value = "t_rag_quality_eval_case", autoResultMap = true)
public class RagQualityEvalCaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private Integer caseIndex;

    private String query;

    private String groundTruthAnswer;

    private String generatedAnswer;

    private String retrievedContext;

    private String tag;

    private Double faithfulness;

    private Double answerRelevancy;

    private Double contextPrecision;

    private Double contextRecall;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> metricRationales;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

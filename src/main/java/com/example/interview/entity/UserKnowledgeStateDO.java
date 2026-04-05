package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_knowledge_state")
public class UserKnowledgeStateDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String topic;

    private Integer difficultyLevel;

    private Double masteryScore;

    private Double confidence;

    private Integer attempts;

    private Double weightedAvgScore;

    private LocalDateTime lastAssessedAt;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}

package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "t_topic_difficulty_level", autoResultMap = true)
public class TopicDifficultyLevelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String topic;

    private Integer difficultyLevel;

    private String category;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> prerequisites;

    private Double avgMasteryScore;

    private Integer totalAssessments;

    private Boolean enabled;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}

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
@TableName(value = "t_learning_trajectory", autoResultMap = true)
public class LearningTrajectoryDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String userId;

    private String topic;

    private String eventType;

    private String source;

    private Integer score;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> weakPoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> familiarPoints;

    private String evidence;

    private LocalDateTime timestamp;

    private LocalDateTime createdAt;
}

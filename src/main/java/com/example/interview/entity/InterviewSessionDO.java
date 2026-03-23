package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.example.interview.core.InterviewSession;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试会话状态实体类
 * 映射数据库表 t_interview_session
 */
@Data
@TableName(value = "t_interview_session", autoResultMap = true)
public class InterviewSessionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String userId;

    private String currentStage;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private InterviewSession contextData;

    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

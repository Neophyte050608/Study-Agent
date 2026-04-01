package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "t_chat_message", autoResultMap = true)
public class ChatMessageDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String sessionId;
    private String role;
    private String contentType;
    private String content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
    @TableLogic
    private Boolean deleted;
    private LocalDateTime createdAt;
}

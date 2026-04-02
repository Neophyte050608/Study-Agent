package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_chat_memory")
public class UserChatMemoryDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String memoryText;
    private String lastSessionId;
    @Version
    private Integer version;
    private LocalDateTime lastDreamAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

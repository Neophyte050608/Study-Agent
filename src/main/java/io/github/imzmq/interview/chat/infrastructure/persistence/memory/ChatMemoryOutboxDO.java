package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_chat_memory_outbox")
public class ChatMemoryOutboxDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long memoryId;
    private String userId;
    private Long memoryVersion;
    private String target;
    private String operation;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime leaseUntil;
    private String leaseToken;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

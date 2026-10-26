package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_chat_memory_source")
public class ChatMemorySourceDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memoryId;
    private String userId;
    private String sessionId;
    private String sourceMessageId;
    private Long sourceMessagePk;
    private Integer sourceStart;
    private Integer sourceEnd;
    private LocalDateTime createdAt;
    private LocalDateTime invalidatedAt;
}

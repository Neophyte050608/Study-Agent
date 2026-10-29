package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_chat_memory_item")
public class ChatMemoryItemDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String category;
    private String subjectKey;
    private String subjectCardinality;
    private String content;
    private String contentHash;
    private String status;
    @Version
    private Long version;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private LocalDateTime originMessageCreatedAt;
    private Long originMessagePk;
    private Integer originQuoteStart;
    private String quarantineReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}

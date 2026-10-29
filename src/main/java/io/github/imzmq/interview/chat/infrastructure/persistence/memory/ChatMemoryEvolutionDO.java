package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_chat_memory_evolution")
public class ChatMemoryEvolutionDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long fromMemoryId;
    private Long toMemoryId;
    private String relationType;
    private LocalDateTime createdAt;
}

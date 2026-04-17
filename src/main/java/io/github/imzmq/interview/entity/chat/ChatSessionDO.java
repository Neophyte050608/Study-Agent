package io.github.imzmq.interview.entity.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "t_chat_session", autoResultMap = true)
public class ChatSessionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String userId;
    private String title;
    private String contextSummary;
    private Long summaryUpToMsgId;
    @TableLogic
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}



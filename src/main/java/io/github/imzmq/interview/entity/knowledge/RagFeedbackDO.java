package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_rag_feedback")
public class RagFeedbackDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String feedbackId;
    private String traceId;
    private String messageId;
    private String userId;
    private String feedbackType;
    private String scene;
    private String queryText;
    private String meta;
    private LocalDateTime createdAt;
}

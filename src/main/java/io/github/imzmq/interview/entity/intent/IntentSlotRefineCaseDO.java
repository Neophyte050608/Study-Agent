package io.github.imzmq.interview.entity.intent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_intent_slot_refine_case")
public class IntentSlotRefineCaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskType;
    private String userQuery;
    private String aiOutput;
    private Integer sortOrder;
    private Boolean enabled;
    @TableLogic
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}




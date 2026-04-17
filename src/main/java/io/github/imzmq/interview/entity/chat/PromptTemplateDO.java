package io.github.imzmq.interview.entity.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_prompt_template")
public class PromptTemplateDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String category;
    private String type;
    private String title;
    private String description;
    private String content;
    private Boolean isBuiltin;
    @TableLogic
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}


package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_knowledge_base")
public class KnowledgeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String status;
    private String sourceType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

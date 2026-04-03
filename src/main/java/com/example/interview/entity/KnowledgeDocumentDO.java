package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_knowledge_document")
public class KnowledgeDocumentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;
    private String docName;
    private String status;
    private Boolean enabled;
    private String sourceType;
    private String sourceLocation;
    private Integer chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

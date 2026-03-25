package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_rag_parent")
public class RagParentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String parentId;
    private String filePath;
    private String sectionPath;
    private String sourceType;
    private String knowledgeTags;
    private String parentText;
    private String parentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

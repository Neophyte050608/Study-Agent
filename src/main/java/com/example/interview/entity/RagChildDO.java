package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_rag_child")
public class RagChildDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String childId;
    private String parentId;
    private Integer childIndex;
    private String childText;
    private String chunkStrategy;
    private String vectorDocId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

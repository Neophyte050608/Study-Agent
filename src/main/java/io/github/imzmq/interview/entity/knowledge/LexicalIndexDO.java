package io.github.imzmq.interview.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 词法索引实体类
 * 映射数据库表 t_lexical_index
 */
@Data
@TableName("t_lexical_index")
public class LexicalIndexDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;

    private String text;

    private String filePath;

    private String knowledgeTags;

    private String sourceType;

    private String parentId;

    private Integer childIndex;

    private String chunkStrategy;

    private LocalDateTime createdAt;
}



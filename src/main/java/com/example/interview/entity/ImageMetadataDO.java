package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_image_metadata")
public class ImageMetadataDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String imageId;
    private String imageName;
    private String filePath;
    private String relativePath;
    private String summaryText;
    private String summaryStatus;
    private String mimeType;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private String fileHash;
    private String textVectorId;
    private String visualVectorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

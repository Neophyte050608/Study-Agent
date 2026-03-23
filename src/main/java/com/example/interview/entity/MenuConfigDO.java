package com.example.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜单配置实体类
 * 映射数据库表 t_menu_config
 */
@Data
@TableName("t_menu_config")
public class MenuConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String menuCode;

    private String title;

    private String description;

    private String path;

    private String icon;

    private String position;

    private Integer sortOrder;

    private Boolean isBeta;

    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.MenuConfigDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜单配置 Mapper 接口
 */
@Mapper
public interface MenuConfigMapper extends BaseMapper<MenuConfigDO> {
}

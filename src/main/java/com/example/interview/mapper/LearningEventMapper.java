package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.LearningEventDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习事件 Mapper 接口
 */
@Mapper
public interface LearningEventMapper extends BaseMapper<LearningEventDO> {
}

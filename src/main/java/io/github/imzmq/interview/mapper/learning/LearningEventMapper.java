package io.github.imzmq.interview.mapper.learning;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.learning.LearningEventDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习事件 Mapper 接口
 */
@Mapper
public interface LearningEventMapper extends BaseMapper<LearningEventDO> {
}



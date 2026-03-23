package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.InterviewSessionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试会话 Mapper 接口
 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionDO> {
}

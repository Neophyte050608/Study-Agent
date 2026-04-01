package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.RagTraceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG Trace 汇总 Mapper。
 */
@Mapper
public interface RagTraceMapper extends BaseMapper<RagTraceDO> {
}

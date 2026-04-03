package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.RagQualityEvalRunDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 生成质量评测运行汇总 Mapper。
 */
@Mapper
public interface RagQualityEvalRunMapper extends BaseMapper<RagQualityEvalRunDO> {
}

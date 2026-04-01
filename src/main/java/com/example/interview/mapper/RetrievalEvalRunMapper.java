package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.RetrievalEvalRunDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索评测运行汇总 Mapper。
 */
@Mapper
public interface RetrievalEvalRunMapper extends BaseMapper<RetrievalEvalRunDO> {
}

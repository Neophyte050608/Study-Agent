package com.example.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.interview.entity.RetrievalEvalCaseDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索评测单样本结果 Mapper。
 */
@Mapper
public interface RetrievalEvalCaseMapper extends BaseMapper<RetrievalEvalCaseDO> {
}

package io.github.imzmq.interview.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RetrievalEvalCaseDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索评测单样本结果 Mapper。
 */
@Mapper
public interface RetrievalEvalCaseMapper extends BaseMapper<RetrievalEvalCaseDO> {
}




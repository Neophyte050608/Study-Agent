package io.github.imzmq.interview.model.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelCandidateMapper extends BaseMapper<ModelCandidateDO> {
}



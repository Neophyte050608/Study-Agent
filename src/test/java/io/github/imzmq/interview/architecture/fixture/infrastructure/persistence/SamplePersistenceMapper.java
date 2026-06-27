package io.github.imzmq.interview.architecture.fixture.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SamplePersistenceMapper extends BaseMapper<SamplePersistenceDO> {
}

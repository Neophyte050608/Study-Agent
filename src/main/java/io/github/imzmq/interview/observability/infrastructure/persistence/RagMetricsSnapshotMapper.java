package io.github.imzmq.interview.observability.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.observability.infrastructure.persistence.RagMetricsSnapshotDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagMetricsSnapshotMapper extends BaseMapper<RagMetricsSnapshotDO> {
}

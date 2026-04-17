package io.github.imzmq.interview.mapper.ingestion;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.ingestion.SyncIndexDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步索引 Mapper 接口
 */
@Mapper
public interface SyncIndexMapper extends BaseMapper<SyncIndexDO> {
}



package io.github.imzmq.interview.media.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.media.infrastructure.persistence.TextImageRelationDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TextImageRelationMapper extends BaseMapper<TextImageRelationDO> {
}



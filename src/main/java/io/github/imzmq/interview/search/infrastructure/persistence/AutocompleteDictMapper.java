package io.github.imzmq.interview.search.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.search.infrastructure.persistence.AutocompleteDictDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AutocompleteDictMapper extends BaseMapper<AutocompleteDictDO> {
}



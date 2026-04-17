package io.github.imzmq.interview.mapper.intent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.intent.IntentNodeDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 意图树节点 Mapper 接口
 */
@Mapper
public interface IntentNodeMapper extends BaseMapper<IntentNodeDO> {
}



package io.github.imzmq.interview.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.UserChatMemoryDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserChatMemoryMapper extends BaseMapper<UserChatMemoryDO> {
}



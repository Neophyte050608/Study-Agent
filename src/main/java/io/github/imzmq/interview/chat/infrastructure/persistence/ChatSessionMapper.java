package io.github.imzmq.interview.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionDO> {

    @Select("""
            SELECT *
            FROM t_chat_session
            WHERE user_id = #{userId}
              AND session_id = #{sessionId}
              AND deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    ChatSessionDO lockOwnedSession(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Update("""
            UPDATE t_chat_session
            SET deleted = 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE user_id = #{userId}
              AND session_id = #{sessionId}
              AND deleted = 0
            """)
    int markOwnedSessionDeleted(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);
}

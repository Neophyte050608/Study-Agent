package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatMemorySourceMapper extends BaseMapper<ChatMemorySourceDO> {

    @Select("""
            SELECT DISTINCT memory_id
            FROM t_chat_memory_source
            WHERE user_id = #{userId}
              AND session_id = #{sessionId}
              AND invalidated_at IS NULL
            ORDER BY memory_id
            """)
    List<Long> selectSessionMemoryIds(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Update("""
            UPDATE t_chat_memory_source
            SET invalidated_at = CURRENT_TIMESTAMP(6)
            WHERE user_id = #{userId}
              AND session_id = #{sessionId}
              AND invalidated_at IS NULL
            """)
    int invalidateSessionSources(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Select("""
            SELECT COUNT(*)
            FROM t_chat_memory_source source
            JOIN t_chat_session session ON session.session_id = source.session_id
            JOIN t_chat_message message ON message.id = source.source_message_pk
            WHERE source.user_id = #{userId}
              AND source.memory_id = #{memoryId}
              AND source.invalidated_at IS NULL
              AND session.user_id = source.user_id
              AND session.deleted = 0
              AND message.deleted = 0
              AND message.role = 'user'
              AND message.message_id = source.source_message_id
              AND message.session_id = source.session_id
            """)
    int countEffectiveSources(
            @Param("userId") String userId,
            @Param("memoryId") long memoryId);

    @Select("""
            <script>
            SELECT DISTINCT source.*
            FROM t_chat_memory_source source
            JOIN t_chat_memory_item item ON item.id = source.memory_id
            JOIN t_chat_session session ON session.session_id = source.session_id
            JOIN t_chat_message message ON message.id = source.source_message_pk
            WHERE source.user_id = #{userId}
              AND item.user_id = source.user_id
              AND source.invalidated_at IS NULL
              AND source.memory_id IN
              <foreach collection="memoryIds" item="memoryId" open="(" separator="," close=")">
                #{memoryId}
              </foreach>
              AND session.user_id = source.user_id
              AND session.deleted = 0
              AND message.deleted = 0
              AND message.role = 'user'
              AND message.id = source.source_message_pk
              AND message.message_id = source.source_message_id
              AND message.session_id = source.session_id
            ORDER BY source.memory_id, source.id
            </script>
            """)
    List<ChatMemorySourceDO> selectAccessibleSources(
            @Param("userId") String userId,
            @Param("memoryIds") List<Long> memoryIds);

    @Update("""
            UPDATE t_chat_memory_source
            SET invalidated_at = CURRENT_TIMESTAMP(6)
            WHERE memory_id = #{memoryId}
              AND user_id = #{userId}
              AND invalidated_at IS NULL
            """)
    int invalidateMemorySources(
            @Param("memoryId") long memoryId,
            @Param("userId") String userId);

    @Select("""
            <script>
            SELECT DISTINCT memory_id
            FROM t_chat_memory_source
            WHERE invalidated_at IS NULL
              AND memory_id IN
              <foreach collection="memoryIds" item="memoryId" open="(" separator="," close=")">
                #{memoryId}
              </foreach>
            </script>
            """)
    List<Long> selectEffectiveMemoryIds(@Param("memoryIds") List<Long> memoryIds);
}

package io.github.imzmq.interview.chat.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {


    @Update("""
            UPDATE t_chat_message message
            JOIN t_chat_session session ON session.session_id = message.session_id
            SET message.deleted = 1
            WHERE session.user_id = #{userId}
              AND session.session_id = #{sessionId}
              AND message.deleted = 0
            """)
    int markSessionMessagesDeleted(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Select("""
            <script>
            SELECT message.*
            FROM t_chat_message message
            JOIN t_chat_session session ON session.session_id = message.session_id
            WHERE session.user_id = #{userId}
              AND session.session_id = #{sessionId}
              AND session.deleted = 0
              AND message.deleted = 0
              AND message.message_id IN
              <foreach item="messageId" collection="messageIds" open="(" separator="," close=")">
                #{messageId}
              </foreach>
            ORDER BY message.id
            </script>
            """)
    List<ChatMessageDO> selectMemorySourceMessages(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds);

    @Select("""
            SELECT message.*
            FROM t_chat_message message
            JOIN t_chat_session session ON session.session_id = message.session_id
            WHERE session.user_id = #{userId}
              AND session.session_id = #{sessionId}
              AND session.deleted = 0
              AND message.deleted = 0
              AND message.id < #{firstSourceMessagePk}
            ORDER BY message.id DESC
            LIMIT #{limit}
            """)
    List<ChatMessageDO> selectMemoryPriorContext(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("firstSourceMessagePk") long firstSourceMessagePk,
            @Param("limit") int limit);
}

package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatMemoryItemMapper extends BaseMapper<ChatMemoryItemDO> {

    @Select("""
            SELECT *
            FROM t_chat_memory_item
            WHERE user_id = #{userId}
              AND category = #{category}
              AND subject_key = #{subjectKey}
            ORDER BY id
            FOR UPDATE
            """)
    List<ChatMemoryItemDO> lockThemeRows(
            @Param("userId") String userId,
            @Param("category") String category,
            @Param("subjectKey") String subjectKey);

    @Update("""
            UPDATE t_chat_memory_item
            SET status = 'SUPERSEDED',
                valid_until = #{validUntil},
                version = #{nextVersion},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{memoryId}
              AND user_id = #{userId}
              AND status = 'ACTIVE'
              AND version = #{currentVersion}
            """)
    int markSuperseded(
            @Param("memoryId") long memoryId,
            @Param("userId") String userId,
            @Param("currentVersion") long currentVersion,
            @Param("nextVersion") long nextVersion,
            @Param("validUntil") LocalDateTime validUntil);

    @Update("""
            UPDATE t_chat_memory_item
            SET status = 'ACTIVE',
                quarantine_reason = NULL,
                version = #{nextVersion},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{memoryId}
              AND user_id = #{userId}
              AND status = 'QUARANTINED'
              AND version = #{currentVersion}
            """)
    int activateQuarantined(
            @Param("memoryId") long memoryId,
            @Param("userId") String userId,
            @Param("currentVersion") long currentVersion,
            @Param("nextVersion") long nextVersion);

    @Update("""
            UPDATE t_chat_memory_item
            SET version = #{nextVersion},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{memoryId}
              AND user_id = #{userId}
              AND status = 'ACTIVE'
              AND version = #{currentVersion}
            """)
    int bumpVersion(
            @Param("memoryId") long memoryId,
            @Param("userId") String userId,
            @Param("currentVersion") long currentVersion,
            @Param("nextVersion") long nextVersion);

    @Select("""
            <script>
            SELECT *
            FROM t_chat_memory_item
            WHERE user_id = #{userId}
            <if test="beforeId != null">
              AND id &lt; #{beforeId}
            </if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ChatMemoryItemDO> selectManagementPage(
            @Param("userId") String userId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM t_chat_memory_item
            WHERE id = #{memoryId}
              AND user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    ChatMemoryItemDO lockOwnedItem(
            @Param("userId") String userId,
            @Param("memoryId") long memoryId);

    @Update("""
            UPDATE t_chat_memory_item
            SET status = 'DELETED',
                content = '',
                version = #{nextVersion},
                deleted_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{memoryId}
              AND user_id = #{userId}
              AND status IN ('ACTIVE', 'QUARANTINED')
              AND version = #{currentVersion}
            """)
    int markDeleted(
            @Param("memoryId") long memoryId,
            @Param("userId") String userId,
            @Param("currentVersion") long currentVersion,
            @Param("nextVersion") long nextVersion);

    @Select("""
            SELECT *
            FROM t_chat_memory_item
            WHERE id > #{afterId}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ChatMemoryItemDO> selectRepairPage(
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT DISTINCT item.*
            FROM t_chat_memory_item item
            JOIN t_chat_memory_source source ON source.memory_id = item.id
            JOIN t_chat_session session ON session.session_id = source.session_id
            JOIN t_chat_message message ON message.id = source.source_message_pk
            WHERE item.user_id = #{userId}
              AND item.status = 'ACTIVE'
              AND item.id IN
              <foreach collection="memoryIds" item="memoryId" open="(" separator="," close=")">
                #{memoryId}
              </foreach>
              AND source.invalidated_at IS NULL
              AND source.user_id = item.user_id
              AND session.user_id = item.user_id
              AND session.deleted = 0
              AND message.deleted = 0
              AND message.role = 'user'
              AND message.id = source.source_message_pk
              AND message.message_id = source.source_message_id
              AND message.session_id = source.session_id
            ORDER BY item.updated_at DESC, item.id DESC
            </script>
            """)
    List<ChatMemoryItemDO> selectRecallItems(
            @Param("userId") String userId,
            @Param("memoryIds") List<Long> memoryIds);

    @Select("""
            SELECT DISTINCT item.*
            FROM t_chat_memory_item item
            JOIN t_chat_memory_source source ON source.memory_id = item.id
            JOIN t_chat_session session ON session.session_id = source.session_id
            JOIN t_chat_message message ON message.id = source.source_message_pk
            WHERE item.user_id = #{userId}
              AND item.status = 'ACTIVE'
              AND source.invalidated_at IS NULL
              AND source.user_id = item.user_id
              AND session.user_id = item.user_id
              AND session.deleted = 0
              AND message.deleted = 0
              AND message.role = 'user'
              AND message.id = source.source_message_pk
              AND message.message_id = source.source_message_id
              AND message.session_id = source.session_id
            ORDER BY item.updated_at DESC, item.id DESC
            LIMIT #{limit}
            """)
    List<ChatMemoryItemDO> selectRecentEffectiveItems(
            @Param("userId") String userId,
            @Param("limit") int limit);
}

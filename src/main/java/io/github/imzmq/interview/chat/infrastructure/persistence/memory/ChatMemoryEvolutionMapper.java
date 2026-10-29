package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMemoryEvolutionMapper extends BaseMapper<ChatMemoryEvolutionDO> {

    @Override
    int insert(ChatMemoryEvolutionDO relation);

    @Select("""
            SELECT relation.to_memory_id
            FROM t_chat_memory_evolution relation
            JOIN t_chat_memory_item source_item
              ON source_item.id = relation.from_memory_id
             AND source_item.user_id = relation.user_id
            JOIN t_chat_memory_item target_item
              ON target_item.id = relation.to_memory_id
             AND target_item.user_id = relation.user_id
            WHERE relation.user_id = #{userId}
              AND relation.from_memory_id = #{memoryId}
              AND relation.relation_type = 'CONFLICTS_WITH'
            ORDER BY relation.to_memory_id
            """)
    List<Long> selectOwnedConflictTargets(
            @Param("userId") String userId,
            @Param("memoryId") long memoryId);

    @Select("""
            SELECT relation.*
            FROM t_chat_memory_evolution relation
            JOIN t_chat_memory_item source_item
              ON source_item.id = relation.from_memory_id
             AND source_item.user_id = relation.user_id
            JOIN t_chat_memory_item target_item
              ON target_item.id = relation.to_memory_id
             AND target_item.user_id = relation.user_id
            WHERE relation.user_id = #{userId}
              AND (relation.from_memory_id = #{memoryId}
                   OR relation.to_memory_id = #{memoryId})
            ORDER BY relation.created_at, relation.id
            """)
    List<ChatMemoryEvolutionDO> selectOwnedRelations(
            @Param("userId") String userId,
            @Param("memoryId") long memoryId);
}

package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatMemoryExtractionTaskMapper extends BaseMapper<ChatMemoryExtractionTaskDO> {


    @Update("""
            UPDATE t_chat_memory_extraction_task
            SET status = 'CANCELLED',
                lease_token = NULL,
                lease_until = NULL,
                error_code = 'SESSION_DELETED',
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE user_id = #{userId}
              AND session_id = #{sessionId}
              AND status IN ('PENDING', 'RETRY', 'PROCESSING')
            """)
    int cancelUnfinishedBySession(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Select("""
            SELECT *
            FROM t_chat_memory_extraction_task
            WHERE task_id = #{taskId}
            LIMIT 1
            """)
    ChatMemoryExtractionTaskDO selectTaskIdentity(@Param("taskId") String taskId);

    @Select("""
            SELECT *
            FROM t_chat_memory_extraction_task
            WHERE task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
              AND lease_until >= #{now}
            LIMIT 1
            FOR UPDATE
            """)
    ChatMemoryExtractionTaskDO lockOwnedTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT *
            FROM t_chat_memory_extraction_task
            WHERE ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= #{now})
                    OR (status = 'PROCESSING' AND lease_until < #{now}))
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<ChatMemoryExtractionTaskDO> lockClaimableTasks(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Update("""
            UPDATE t_chat_memory_extraction_task
            SET status = 'PROCESSING',
                lease_token = #{leaseToken},
                lease_until = #{leaseUntil},
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= #{now})
                    OR (status = 'PROCESSING' AND lease_until < #{now}))
            """)
    int leaseTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_extraction_task
            SET status = 'DONE',
                lease_token = NULL,
                lease_until = NULL,
                error_code = NULL,
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
            """)
    int markTaskDone(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_extraction_task
            SET status = 'RETRY',
                attempt_count = #{attemptCount},
                next_attempt_at = #{nextAttemptAt},
                lease_token = NULL,
                lease_until = NULL,
                error_code = #{errorCode},
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
            """)
    int rescheduleTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("attemptCount") int attemptCount,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_extraction_task
            SET status = 'DEAD',
                attempt_count = #{attemptCount},
                lease_token = NULL,
                lease_until = NULL,
                error_code = #{errorCode},
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
            """)
    int markTaskDead(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("attemptCount") int attemptCount,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_extraction_task
            SET status = 'PENDING',
                attempt_count = 0,
                next_attempt_at = #{now},
                lease_token = NULL,
                lease_until = NULL,
                error_code = NULL,
                updated_at = #{now}
            WHERE task_id = #{taskId}
              AND status = 'DEAD'
            """)
    int resetDeadTask(
            @Param("taskId") String taskId,
            @Param("now") LocalDateTime now);
}

package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatMemoryOutboxMapper extends BaseMapper<ChatMemoryOutboxDO> {

    @Select("""
            SELECT *
            FROM t_chat_memory_outbox
            WHERE ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= #{now})
                    OR (status = 'PROCESSING' AND lease_until < #{now}))
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<ChatMemoryOutboxDO> lockClaimableEvents(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Update("""
            UPDATE t_chat_memory_outbox
            SET status = 'PROCESSING',
                lease_token = #{leaseToken},
                lease_until = #{leaseUntil},
                updated_at = #{now}
            WHERE event_id = #{eventId}
              AND ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= #{now})
                    OR (status = 'PROCESSING' AND lease_until < #{now}))
            """)
    int leaseEvent(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_outbox
            SET status = 'DONE',
                lease_token = NULL,
                lease_until = NULL,
                error_code = NULL,
                updated_at = #{now}
            WHERE event_id = #{eventId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
            """)
    int completeEvent(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_outbox
            SET status = 'RETRY',
                attempt_count = #{attemptCount},
                next_attempt_at = #{nextAttemptAt},
                lease_token = NULL,
                lease_until = NULL,
                error_code = #{errorCode},
                updated_at = #{now}
            WHERE event_id = #{eventId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
            """)
    int rescheduleEvent(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("attemptCount") int attemptCount,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE t_chat_memory_outbox
            SET status = 'DEAD',
                attempt_count = #{attemptCount},
                lease_token = NULL,
                lease_until = NULL,
                error_code = #{errorCode},
                updated_at = #{now}
            WHERE event_id = #{eventId}
              AND lease_token = #{leaseToken}
              AND status = 'PROCESSING'
            """)
    int markEventDead(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("attemptCount") int attemptCount,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now);
}

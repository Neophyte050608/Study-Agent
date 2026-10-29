package io.github.imzmq.interview.chat.infrastructure.persistence.memory;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryLeaseSqlTest {

    @Test
    void extractionTaskClaimUsesSkipLockedAndBoundedLimit() throws Exception {
        Method method = ChatMemoryExtractionTaskMapper.class.getMethod(
                "lockClaimableTasks", LocalDateTime.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .containsIgnoringCase("FOR UPDATE SKIP LOCKED")
                .contains("LIMIT #{limit}")
                .contains("status = 'PROCESSING'")
                .contains("lease_until < #{now}")
                .doesNotContain("LIMIT 50");
    }

    @Test
    void taskCompletionAndRetryAreFencedByLeaseToken() throws Exception {
        Method done = ChatMemoryExtractionTaskMapper.class.getMethod(
                "markTaskDone", String.class, String.class, LocalDateTime.class);
        Method retry = ChatMemoryExtractionTaskMapper.class.getMethod(
                "rescheduleTask", String.class, String.class, int.class,
                LocalDateTime.class, String.class, LocalDateTime.class);
        Method dead = ChatMemoryExtractionTaskMapper.class.getMethod(
                "markTaskDead", String.class, String.class, int.class,
                String.class, LocalDateTime.class);
        Method owned = ChatMemoryExtractionTaskMapper.class.getMethod(
                "lockOwnedTask", String.class, String.class, LocalDateTime.class);

        assertThat(String.join(" ", done.getAnnotation(Update.class).value()))
                .contains("task_id = #{taskId}")
                .contains("lease_token = #{leaseToken}");
        assertThat(String.join(" ", retry.getAnnotation(Update.class).value()))
                .contains("task_id = #{taskId}")
                .contains("lease_token = #{leaseToken}");
        assertThat(String.join(" ", dead.getAnnotation(Update.class).value()))
                .contains("task_id = #{taskId}")
                .contains("lease_token = #{leaseToken}")
                .contains("status = 'PROCESSING'");
        assertThat(String.join(" ", owned.getAnnotation(Select.class).value()))
                .contains("lease_token = #{leaseToken}")
                .contains("lease_until >= #{now}")
                .containsIgnoringCase("FOR UPDATE");
    }

    @Test
    void lifecycleThemeLockScopesOwnerAndSubjectInStableOrder() throws Exception {
        Method method = ChatMemoryItemMapper.class.getMethod(
                "lockThemeRows", String.class, String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("user_id = #{userId}")
                .contains("category = #{category}")
                .contains("subject_key = #{subjectKey}")
                .containsIgnoringCase("ORDER BY id")
                .containsIgnoringCase("FOR UPDATE");
    }

    @Test
    void lifecycleStateTransitionsAreFencedByOwnerStatusAndVersion() throws Exception {
        Method supersede = ChatMemoryItemMapper.class.getMethod(
                "markSuperseded", long.class, String.class, long.class, long.class,
                LocalDateTime.class);
        Method activate = ChatMemoryItemMapper.class.getMethod(
                "activateQuarantined", long.class, String.class, long.class, long.class);

        assertThat(String.join(" ", supersede.getAnnotation(Update.class).value()))
                .contains("id = #{memoryId}")
                .contains("user_id = #{userId}")
                .contains("status = 'ACTIVE'")
                .contains("version = #{currentVersion}")
                .contains("status = 'SUPERSEDED'")
                .contains("valid_until = #{validUntil}")
                .contains("version = #{nextVersion}");
        assertThat(String.join(" ", activate.getAnnotation(Update.class).value()))
                .contains("id = #{memoryId}")
                .contains("user_id = #{userId}")
                .contains("status = 'QUARANTINED'")
                .contains("version = #{currentVersion}")
                .contains("status = 'ACTIVE'")
                .contains("quarantine_reason = NULL")
                .contains("version = #{nextVersion}");
    }

    @Test
    void outboxClaimUsesSkipLockedAndCompletionIsFenced() throws Exception {
        Method claim = ChatMemoryOutboxMapper.class.getMethod(
                "lockClaimableEvents", LocalDateTime.class, int.class);
        Method lease = ChatMemoryOutboxMapper.class.getMethod(
                "leaseEvent", String.class, String.class, LocalDateTime.class, LocalDateTime.class);
        Method complete = ChatMemoryOutboxMapper.class.getMethod(
                "completeEvent", String.class, String.class, LocalDateTime.class);
        Method retry = ChatMemoryOutboxMapper.class.getMethod(
                "rescheduleEvent", String.class, String.class, int.class,
                LocalDateTime.class, String.class, LocalDateTime.class);
        Method dead = ChatMemoryOutboxMapper.class.getMethod(
                "markEventDead", String.class, String.class, int.class,
                String.class, LocalDateTime.class);

        assertThat(String.join(" ", claim.getAnnotation(Select.class).value()))
                .containsIgnoringCase("FOR UPDATE SKIP LOCKED")
                .contains("LIMIT #{limit}")
                .contains("status = 'PROCESSING'")
                .contains("lease_until < #{now}");
        assertThat(String.join(" ", lease.getAnnotation(Update.class).value()))
                .contains("event_id = #{eventId}")
                .contains("status = 'PROCESSING'")
                .contains("lease_until < #{now}");
        for (Method method : new Method[]{complete, retry, dead}) {
            assertThat(String.join(" ", method.getAnnotation(Update.class).value()))
                    .contains("event_id = #{eventId}")
                    .contains("lease_token = #{leaseToken}")
                    .contains("status = 'PROCESSING'");
        }
    }
}

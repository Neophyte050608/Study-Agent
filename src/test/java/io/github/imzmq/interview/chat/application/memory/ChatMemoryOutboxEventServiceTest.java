package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryOutboxEventServiceTest {

    private final ChatMemoryOutboxMapper mapper = mock(ChatMemoryOutboxMapper.class);
    private final ChatMemoryOutboxEventService service = new ChatMemoryOutboxEventService(mapper);

    @Test
    void enqueueResetsAnExistingRetryForTheNormalCommitPath() {
        ChatMemoryOutboxDO existing = event("RETRY");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.enqueue(10L, "user-1", 2L, "UPSERT");

        assertThat(existing.getStatus()).isEqualTo("PENDING");
        assertThat(existing.getAttemptCount()).isZero();
        assertThat(existing.getLeaseToken()).isNull();
        assertThat(existing.getErrorCode()).isNull();
        verify(mapper).updateById(existing);
    }

    @Test
    void repairReactivationLeavesAnInFlightEventUntouched() {
        ChatMemoryOutboxDO existing = event("PROCESSING");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.reactivateTerminal(10L, "user-1", 2L, "UPSERT");

        assertThat(existing.getStatus()).isEqualTo("PROCESSING");
        verify(mapper, never()).updateById(existing);
        verify(mapper, never()).insert(any());
    }

    private ChatMemoryOutboxDO event(String status) {
        ChatMemoryOutboxDO event = new ChatMemoryOutboxDO();
        event.setEventId("event-1");
        event.setMemoryId(10L);
        event.setUserId("user-1");
        event.setMemoryVersion(2L);
        event.setTarget("MILVUS");
        event.setOperation("UPSERT");
        event.setStatus(status);
        event.setAttemptCount(4);
        event.setLeaseToken("lease");
        event.setErrorCode("error");
        return event;
    }
}

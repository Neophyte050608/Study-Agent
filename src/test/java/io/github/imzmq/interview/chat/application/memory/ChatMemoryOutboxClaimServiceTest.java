package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryOutboxClaimServiceTest {

    private final ChatMemoryOutboxMapper mapper = mock(ChatMemoryOutboxMapper.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private final ChatMemoryOutboxClaimService service =
            new ChatMemoryOutboxClaimService(mapper, properties);

    @Test
    void claimBoundaryIsTransactional() throws Exception {
        Method method = ChatMemoryOutboxClaimService.class.getMethod("claimAvailableEvents");
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void claimsAtMostConfiguredPageAndOnlyPublishesSuccessfulLeases() {
        properties.setWorkerBatchSize(200);
        properties.setWorkerLease(java.time.Duration.ofSeconds(60));
        ChatMemoryOutboxDO first = event("event-1");
        ChatMemoryOutboxDO raced = event("event-2");
        when(mapper.lockClaimableEvents(any(), eq(50))).thenReturn(List.of(first, raced));
        when(mapper.leaseEvent(eq("event-1"), any(), any(), any())).thenReturn(1);
        when(mapper.leaseEvent(eq("event-2"), any(), any(), any())).thenReturn(0);

        List<ChatMemoryOutboxDO> claimed = service.claimAvailableEvents();

        assertThat(claimed).containsExactly(first);
        assertThat(first.getStatus()).isEqualTo("PROCESSING");
        assertThat(first.getLeaseToken()).isNotBlank();
        assertThat(first.getLeaseUntil()).isAfter(LocalDateTime.now());
        verify(mapper).lockClaimableEvents(any(), eq(50));
    }

    private ChatMemoryOutboxDO event(String eventId) {
        ChatMemoryOutboxDO event = new ChatMemoryOutboxDO();
        event.setEventId(eventId);
        event.setStatus("PENDING");
        event.setAttemptCount(0);
        return event;
    }
}

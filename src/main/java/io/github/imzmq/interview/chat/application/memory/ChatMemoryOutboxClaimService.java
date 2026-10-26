package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatMemoryOutboxClaimService {

    private static final int MAX_PROJECTION_BATCH = 50;

    private final ChatMemoryOutboxMapper outboxMapper;
    private final ChatMemoryProperties properties;

    public ChatMemoryOutboxClaimService(
            ChatMemoryOutboxMapper outboxMapper,
            ChatMemoryProperties properties) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
    }

    @Transactional
    public List<ChatMemoryOutboxDO> claimAvailableEvents() {
        LocalDateTime now = LocalDateTime.now();
        int limit = Math.min(properties.getWorkerBatchSize(), MAX_PROJECTION_BATCH);
        List<ChatMemoryOutboxDO> claimable = outboxMapper.lockClaimableEvents(now, limit);
        List<ChatMemoryOutboxDO> claimed = new ArrayList<>(claimable.size());
        for (ChatMemoryOutboxDO event : claimable) {
            String leaseToken = UUID.randomUUID().toString();
            LocalDateTime leaseUntil = now.plus(properties.getWorkerLease());
            if (outboxMapper.leaseEvent(event.getEventId(), leaseToken, leaseUntil, now) == 1) {
                event.setStatus("PROCESSING");
                event.setLeaseToken(leaseToken);
                event.setLeaseUntil(leaseUntil);
                event.setUpdatedAt(now);
                claimed.add(event);
            }
        }
        return List.copyOf(claimed);
    }
}

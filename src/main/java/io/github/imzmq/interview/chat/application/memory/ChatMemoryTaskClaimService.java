package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatMemoryTaskClaimService {

    private static final int MAX_EXTRACTION_BATCH = 8;

    private final ChatMemoryExtractionTaskMapper taskMapper;
    private final ChatMemoryProperties properties;

    public ChatMemoryTaskClaimService(
            ChatMemoryExtractionTaskMapper taskMapper,
            ChatMemoryProperties properties) {
        this.taskMapper = taskMapper;
        this.properties = properties;
    }

    @Transactional
    public List<ChatMemoryExtractionTaskDO> claimAvailableTasks() {
        LocalDateTime now = LocalDateTime.now();
        int limit = Math.min(properties.getExtractionBatchSize(), MAX_EXTRACTION_BATCH);
        List<ChatMemoryExtractionTaskDO> claimable = taskMapper.lockClaimableTasks(now, limit);
        List<ChatMemoryExtractionTaskDO> claimed = new ArrayList<>(claimable.size());
        for (ChatMemoryExtractionTaskDO task : claimable) {
            String leaseToken = UUID.randomUUID().toString();
            LocalDateTime leaseUntil = now.plus(properties.getWorkerLease());
            if (taskMapper.leaseTask(task.getTaskId(), leaseToken, leaseUntil, now) == 1) {
                task.setStatus("PROCESSING");
                task.setLeaseToken(leaseToken);
                task.setLeaseUntil(leaseUntil);
                task.setUpdatedAt(now);
                claimed.add(task);
            }
        }
        return List.copyOf(claimed);
    }
}

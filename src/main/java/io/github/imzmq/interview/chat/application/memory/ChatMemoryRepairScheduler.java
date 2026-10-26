package io.github.imzmq.interview.chat.application.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.chat.memory.enabled", havingValue = "true")
public class ChatMemoryRepairScheduler {

    private final ChatMemoryRepairService repairService;
    private final AtomicBoolean running = new AtomicBoolean();

    public ChatMemoryRepairScheduler(ChatMemoryRepairService repairService) {
        this.repairService = repairService;
    }

    @Scheduled(fixedDelayString = "${app.chat.memory.repair-delay:30m}")
    public void repair() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping overlapping Chat memory projection repair");
            return;
        }
        try {
            ChatMemoryRepairService.RepairSummary summary = repairService.repairAll();
            log.info("Chat memory projection repair completed: truthRows={}, projectionRows={}, queuedEvents={}",
                    summary.truthRows(), summary.projectionRows(), summary.queuedEvents());
        } catch (RuntimeException exception) {
            log.warn("Chat memory projection repair failed", exception);
        } finally {
            running.set(false);
        }
    }
}

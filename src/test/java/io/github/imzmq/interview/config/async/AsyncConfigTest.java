package io.github.imzmq.interview.config.async;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void routingUnderstandingExecutorDoesNotQueueStaleShadowRequests() {
        Executor configured = new AsyncConfig().routingUnderstandingExecutor();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configured;
        try {
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        } finally {
            executor.shutdown();
        }
    }
}

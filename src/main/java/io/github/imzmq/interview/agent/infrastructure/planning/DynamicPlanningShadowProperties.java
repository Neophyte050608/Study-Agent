package io.github.imzmq.interview.agent.infrastructure.planning;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "app.agent.dynamic-planning.shadow")
public class DynamicPlanningShadowProperties {
    private Duration timeout = Duration.ofSeconds(2);
    private int workers = 1;
    private int queueCapacity = 8;

    @PostConstruct
    public void validate() {
        if (timeout == null
                || timeout.toMillis() < 100 || timeout.toMillis() > 10_000
                || workers < 1 || workers > 2
                || queueCapacity < 1 || queueCapacity > 32) {
            throw new IllegalArgumentException("INVALID_DYNAMIC_PLANNING_SHADOW_LIMITS");
        }
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}

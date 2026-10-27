package io.github.imzmq.interview.agent.application.execution;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Small bounded controls; physical node workers remain shared with synchronous previews. */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "app.agent.execution")
public class DurableExecutionProperties {
    private boolean enabled;
    private int coordinators = 2;
    private int pollSeconds = 1;
    private int leaseSeconds = 30;
    private int heartbeatSeconds = 5;
    @PostConstruct public void validate() {
        if (coordinators < 1 || coordinators > 2 || pollSeconds < 1 || pollSeconds > 30
                || leaseSeconds < 10 || leaseSeconds > 120 || heartbeatSeconds < 1
                || heartbeatSeconds > leaseSeconds / 3) {
            throw new IllegalArgumentException("INVALID_DURABLE_EXECUTION_LIMITS");
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCoordinators() { return coordinators; }
    public void setCoordinators(int value) { coordinators = value; }
    public int getPollSeconds() { return pollSeconds; }
    public void setPollSeconds(int value) { pollSeconds = value; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = value; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int value) { heartbeatSeconds = value; }
}

package io.github.imzmq.interview.config.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.routing.understanding.shadow")
public class RoutingUnderstandingProperties {

    private boolean enabled;
    private long timeoutMillis = 3000L;
    private int failureThreshold = 3;
    private long openDurationMillis = 30000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTimeoutMillis() {
        return Math.max(1L, Math.min(timeoutMillis, 3000L));
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getFailureThreshold() {
        return Math.max(1, failureThreshold);
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getOpenDurationMillis() {
        return Math.max(1L, openDurationMillis);
    }

    public void setOpenDurationMillis(long openDurationMillis) {
        this.openDurationMillis = openDurationMillis;
    }
}

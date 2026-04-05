package com.example.interview.modelrouting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.model-routing")
public class ModelRoutingProperties {

    private boolean enabled = false;
    private String defaultModel = "";
    private String deepThinkingModel = "";
    private List<Candidate> candidates = new ArrayList<>();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Stream stream = new Stream();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getDeepThinkingModel() {
        return deepThinkingModel;
    }

    public void setDeepThinkingModel(String deepThinkingModel) {
        this.deepThinkingModel = deepThinkingModel;
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<Candidate> candidates) {
        this.candidates = candidates == null ? new ArrayList<>() : candidates;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null ? new CircuitBreaker() : circuitBreaker;
    }

    public Stream getStream() {
        return stream;
    }

    public void setStream(Stream stream) {
        this.stream = stream == null ? new Stream() : stream;
    }

    public static class Candidate {
        private String name = "";
        private String provider = "openai";
        private String model = "";
        private String beanName = "";
        private boolean enabled = true;
        private int priority = 100;
        private boolean supportsThinking = false;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBeanName() {
            return beanName;
        }

        public void setBeanName(String beanName) {
            this.beanName = beanName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public boolean isSupportsThinking() {
            return supportsThinking;
        }

        public void setSupportsThinking(boolean supportsThinking) {
            this.supportsThinking = supportsThinking;
        }
    }

    public static class CircuitBreaker {
        private int failureThreshold = 3;
        private long openDurationMs = 30000;
        private int halfOpenMaxTrials = 1;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenDurationMs() {
            return openDurationMs;
        }

        public void setOpenDurationMs(long openDurationMs) {
            this.openDurationMs = openDurationMs;
        }

        public int getHalfOpenMaxTrials() {
            return halfOpenMaxTrials;
        }

        public void setHalfOpenMaxTrials(int halfOpenMaxTrials) {
            this.halfOpenMaxTrials = halfOpenMaxTrials;
        }
    }

    public static class Stream {
        private long firstPacketTimeoutMs = 6000;
        private long totalResponseTimeoutMs = 60000;
        private int firstPacketMinChars = 1;
        private int bufferChunkSize = 32;

        public long getFirstPacketTimeoutMs() {
            return firstPacketTimeoutMs;
        }

        public void setFirstPacketTimeoutMs(long firstPacketTimeoutMs) {
            this.firstPacketTimeoutMs = firstPacketTimeoutMs;
        }

        public long getTotalResponseTimeoutMs() {
            return totalResponseTimeoutMs;
        }

        public void setTotalResponseTimeoutMs(long totalResponseTimeoutMs) {
            this.totalResponseTimeoutMs = totalResponseTimeoutMs;
        }

        public int getFirstPacketMinChars() {
            return firstPacketMinChars;
        }

        public void setFirstPacketMinChars(int firstPacketMinChars) {
            this.firstPacketMinChars = firstPacketMinChars;
        }

        public int getBufferChunkSize() {
            return bufferChunkSize;
        }

        public void setBufferChunkSize(int bufferChunkSize) {
            this.bufferChunkSize = bufferChunkSize;
        }
    }
}

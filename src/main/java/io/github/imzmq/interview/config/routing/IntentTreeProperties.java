package io.github.imzmq.interview.config.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图树基础配置属性类。
 * 仅用于加载 application.yml 中的基础控制参数。
 * 复杂的意图节点现已迁移至 MySQL 和 IntentTreeService 进行管理。
 */
@Component
@ConfigurationProperties(prefix = "app.intent-tree")
public class IntentTreeProperties {

    private boolean enabled = true;
    private double confidenceThreshold = 0.6;
    private double minGap = 0.12;
    private double ambiguityRatio = 0.8;
    private long clarificationTtlMinutes = 10;
    private int maxCandidates = 3;
    private boolean fallbackToLegacyTaskRouter = true;

    private List<LeafIntentConfig> leafIntents = new ArrayList<>();
    private List<SlotRefineCase> slotRefineCases = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getMinGap() {
        return minGap;
    }

    public void setMinGap(double minGap) {
        this.minGap = minGap;
    }

    public double getAmbiguityRatio() {
        return ambiguityRatio;
    }

    public void setAmbiguityRatio(double ambiguityRatio) {
        this.ambiguityRatio = ambiguityRatio;
    }

    public long getClarificationTtlMinutes() {
        return clarificationTtlMinutes;
    }

    public void setClarificationTtlMinutes(long clarificationTtlMinutes) {
        this.clarificationTtlMinutes = clarificationTtlMinutes;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public boolean isFallbackToLegacyTaskRouter() {
        return fallbackToLegacyTaskRouter;
    }

    public void setFallbackToLegacyTaskRouter(boolean fallbackToLegacyTaskRouter) {
        this.fallbackToLegacyTaskRouter = fallbackToLegacyTaskRouter;
    }

    public List<LeafIntentConfig> getLeafIntents() {
        return leafIntents;
    }

    public void setLeafIntents(List<LeafIntentConfig> leafIntents) {
        this.leafIntents = leafIntents == null ? new ArrayList<>() : leafIntents;
    }

    public List<SlotRefineCase> getSlotRefineCases() {
        return slotRefineCases;
    }

    public void setSlotRefineCases(List<SlotRefineCase> slotRefineCases) {
        this.slotRefineCases = slotRefineCases == null ? new ArrayList<>() : slotRefineCases;
    }

    public static class LeafIntentConfig {
        private String intentId = "";
        private String path = "";
        private String name = "";
        private String description = "";
        private String taskType = "";
        private List<String> examples = new ArrayList<>();
        private List<String> slotHints = new ArrayList<>();

        public String getIntentId() {
            return intentId;
        }

        public void setIntentId(String intentId) {
            this.intentId = intentId;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getTaskType() {
            return taskType;
        }

        public void setTaskType(String taskType) {
            this.taskType = taskType;
        }

        public List<String> getExamples() {
            return examples;
        }

        public void setExamples(List<String> examples) {
            this.examples = examples == null ? new ArrayList<>() : examples;
        }

        public List<String> getSlotHints() {
            return slotHints;
        }

        public void setSlotHints(List<String> slotHints) {
            this.slotHints = slotHints == null ? new ArrayList<>() : slotHints;
        }
    }

    public static class SlotRefineCase {
        private String taskType = "";
        private String userQuery = "";
        private String aiOutput = "";

        public String getTaskType() {
            return taskType;
        }

        public void setTaskType(String taskType) {
            this.taskType = taskType;
        }

        public String getUserQuery() {
            return userQuery;
        }

        public void setUserQuery(String userQuery) {
            this.userQuery = userQuery;
        }

        public String getAiOutput() {
            return aiOutput;
        }

        public void setAiOutput(String aiOutput) {
            this.aiOutput = aiOutput;
        }
    }
}


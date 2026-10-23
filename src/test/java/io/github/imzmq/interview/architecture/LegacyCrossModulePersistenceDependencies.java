package io.github.imzmq.interview.architecture;

import java.util.LinkedHashSet;
import java.util.Set;

final class LegacyCrossModulePersistenceDependencies {

    private static final Set<LegacyDependency> DEPENDENCIES = Set.of(
            dependency("io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent",
                    "io.github.imzmq.interview.interview.infrastructure.persistence.SessionRepository", "M5"),
            dependency("io.github.imzmq.interview.agent.runtime.RollingSummaryAgent",
                    "io.github.imzmq.interview.interview.infrastructure.persistence.InterviewSessionMapper", "M5"),
            dependency("io.github.imzmq.interview.agent.runtime.RollingSummaryAgent",
                    "io.github.imzmq.interview.interview.infrastructure.persistence.SessionRepository", "M5"),
            dependency("io.github.imzmq.interview.feedback.application.FeedbackApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackDO", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.FeedbackApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackMapper", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotDO", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotMapper", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagTraceDO", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagTraceMapper", "M7"),
            dependency("io.github.imzmq.interview.interview.api.ChatController",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.interview.api.ChatController",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO", "M3"),
            dependency("io.github.imzmq.interview.interview.api.CodingPracticeController",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M4"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper", "M3"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO", "M3"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper", "M3"),
            dependency("io.github.imzmq.interview.interview.application.ChatStreamingService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackDO", "M7"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackMapper", "M7"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotDO", "M7"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotMapper", "M7"),
            dependency("io.github.imzmq.interview.knowledge.application.chatstream.ChatStreamingSupport",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.media.application.ImageService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentDO", "M7"),
            dependency("io.github.imzmq.interview.media.application.ImageService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentMapper", "M7"),
            dependency("io.github.imzmq.interview.search.application.PersonalRanker",
                    "io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateDO", "M7"),
            dependency("io.github.imzmq.interview.search.application.PersonalRanker",
                    "io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateMapper", "M7")
    );

    private LegacyCrossModulePersistenceDependencies() {
    }

    static Set<LegacyDependency> all() {
        return DEPENDENCIES;
    }

    static Set<ModuleBoundaryRules.DependencyKey> keys() {
        Set<ModuleBoundaryRules.DependencyKey> keys = new LinkedHashSet<>();
        for (LegacyDependency dependency : DEPENDENCIES) {
            keys.add(dependency.key());
        }
        return Set.copyOf(keys);
    }

    private static LegacyDependency dependency(String origin, String target, String milestone) {
        return new LegacyDependency(new ModuleBoundaryRules.DependencyKey(origin, target), milestone);
    }

    record LegacyDependency(ModuleBoundaryRules.DependencyKey key, String cleanupMilestone) {

        LegacyDependency {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            if (cleanupMilestone == null || cleanupMilestone.isBlank()) {
                throw new IllegalArgumentException("cleanupMilestone must not be blank");
            }
        }
    }
}

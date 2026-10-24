package io.github.imzmq.interview.architecture;

import java.util.LinkedHashSet;
import java.util.Set;

final class LegacyCrossModulePersistenceDependencies {

    private static final Set<LegacyDependency> DEPENDENCIES = Set.of(
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

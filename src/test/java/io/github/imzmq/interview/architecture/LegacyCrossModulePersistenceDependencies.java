package io.github.imzmq.interview.architecture;

import java.util.Set;

final class LegacyCrossModulePersistenceDependencies {

    private LegacyCrossModulePersistenceDependencies() {
    }

    static Set<ModuleBoundaryRules.DependencyKey> keys() {
        return Set.of();
    }
}

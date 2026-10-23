package io.github.imzmq.interview.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

final class ModuleBoundaryRules {

    static final String ROOT_PACKAGE = "io.github.imzmq.interview";
    private static final String ROOT_PREFIX = ROOT_PACKAGE + ".";
    private static final String PERSISTENCE_SEGMENT = ".infrastructure.persistence";

    private ModuleBoundaryRules() {
    }

    static ArchRule noCrossModulePersistenceAccess(Set<DependencyKey> allowedDependencies) {
        Set<DependencyKey> immutableAllowlist = Set.copyOf(allowedDependencies);
        return classes()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .should(notAccessAnotherModulesPersistence(immutableAllowlist));
    }

    static Set<DependencyKey> findCrossModulePersistenceDependencies(JavaClasses classes) {
        Set<DependencyKey> dependencies = new LinkedHashSet<>();
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                if (isCrossModulePersistenceDependency(dependency)) {
                    dependencies.add(DependencyKey.from(dependency));
                }
            }
        }
        return Set.copyOf(dependencies);
    }

    private static ArchCondition<JavaClass> notAccessAnotherModulesPersistence(
            Set<DependencyKey> allowedDependencies
    ) {
        return new ArchCondition<>("not access another top-level module's infrastructure.persistence") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    if (!isCrossModulePersistenceDependency(dependency)) {
                        continue;
                    }
                    DependencyKey key = DependencyKey.from(dependency);
                    if (!allowedDependencies.contains(key)) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                key.originClassName() + " depends on " + key.targetClassName()
                        ));
                    }
                }
            }
        };
    }

    private static boolean isCrossModulePersistenceDependency(Dependency dependency) {
        JavaClass origin = dependency.getOriginClass();
        JavaClass target = dependency.getTargetClass();
        Optional<String> originModule = topLevelModule(origin.getPackageName());
        Optional<String> targetModule = topLevelModule(target.getPackageName());

        return target.getPackageName().contains(PERSISTENCE_SEGMENT)
                && originModule.isPresent()
                && targetModule.isPresent()
                && !originModule.get().equals(targetModule.get());
    }

    private static Optional<String> topLevelModule(String packageName) {
        if (!packageName.startsWith(ROOT_PREFIX)) {
            return Optional.empty();
        }
        String remainder = packageName.substring(ROOT_PREFIX.length());
        int separator = remainder.indexOf('.');
        return Optional.of(separator < 0 ? remainder : remainder.substring(0, separator));
    }

    record DependencyKey(String originClassName, String targetClassName) {

        DependencyKey {
            if (originClassName == null || originClassName.isBlank()) {
                throw new IllegalArgumentException("originClassName must not be blank");
            }
            if (targetClassName == null || targetClassName.isBlank()) {
                throw new IllegalArgumentException("targetClassName must not be blank");
            }
        }

        private static DependencyKey from(Dependency dependency) {
            return new DependencyKey(
                    dependency.getOriginClass().getName(),
                    dependency.getTargetClass().getName()
            );
        }
    }
}

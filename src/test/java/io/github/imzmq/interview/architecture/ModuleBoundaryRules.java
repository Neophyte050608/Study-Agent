package io.github.imzmq.interview.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.Priority;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

final class ModuleBoundaryRules {

    static final String ROOT_PACKAGE = "io.github.imzmq.interview";
    private static final String ROOT_PREFIX = ROOT_PACKAGE + ".";
    private static final String PERSISTENCE_SEGMENT = ".infrastructure.persistence";
    private static final String APPLICATION_SEGMENT = ".application";
    private static final String APPLICATION_CONTRACT_SEGMENT = ".application.contract";

    static final Set<String> MODULES = Set.of(
            "agent", "bootstrap", "chat", "common", "config", "feedback", "identity", "im",
            "ingestion", "interview", "knowledge", "learning", "mcp", "media", "menu",
            "modelrouting", "observability", "practice", "routing", "search", "security", "skill", "tool"
    );
    private static final Set<String> TECHNICAL_COMPOSITION_ROOTS = Set.of("bootstrap", "config");

    private ModuleBoundaryRules() {
    }

    static ArchRule onlyAccessOtherModulesThroughApplicationContracts() {
        return onlyAccessOtherModulesThroughApplicationContracts(MODULES, TECHNICAL_COMPOSITION_ROOTS);
    }

    static ArchRule onlyAccessOtherModulesThroughApplicationContracts(Set<String> modules) {
        return onlyAccessOtherModulesThroughApplicationContracts(modules, Set.of());
    }

    private static ArchRule onlyAccessOtherModulesThroughApplicationContracts(
            Set<String> modules,
            Set<String> compositionRoots
    ) {
        Set<String> knownModules = Set.copyOf(modules);
        Set<String> allowedOrigins = Set.copyOf(compositionRoots);
        return classes()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .should(notAccessAnotherModulesApplicationImplementation(knownModules, allowedOrigins));
    }

    static ArchRule topLevelModulesShouldBeAcyclic() {
        return topLevelModulesShouldBeAcyclic(MODULES, TECHNICAL_COMPOSITION_ROOTS);
    }

    static ArchRule topLevelModulesShouldBeAcyclic(Set<String> modules) {
        return topLevelModulesShouldBeAcyclic(modules, Set.of());
    }

    private static ArchRule topLevelModulesShouldBeAcyclic(
            Set<String> modules,
            Set<String> compositionRoots
    ) {
        return new TopLevelModulesAcyclicRule(
                Set.copyOf(modules),
                Set.copyOf(compositionRoots),
                "top-level modules should have no cycles through public application contracts"
        );
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

    private static ArchCondition<JavaClass> notAccessAnotherModulesApplicationImplementation(
            Set<String> modules,
            Set<String> compositionRoots
    ) {
        return new ArchCondition<>("access another top-level module's application only through application.contract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    if (!isCrossModuleApplicationImplementationDependency(dependency, modules, compositionRoots)) {
                        continue;
                    }
                    DependencyKey key = DependencyKey.from(dependency);
                    events.add(SimpleConditionEvent.violated(
                            item,
                            key.originClassName() + " depends on non-public application type " + key.targetClassName()
                    ));
                }
            }
        };
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

    private static boolean isCrossModuleApplicationImplementationDependency(
            Dependency dependency,
            Set<String> modules,
            Set<String> compositionRoots
    ) {
        JavaClass origin = dependency.getOriginClass();
        JavaClass target = dependency.getTargetClass();
        Optional<String> originModule = topLevelModule(origin.getPackageName(), modules);
        Optional<String> targetModule = topLevelModule(target.getPackageName(), modules);
        String targetPackage = target.getPackageName();

        return originModule.isPresent()
                && targetModule.isPresent()
                && !originModule.get().equals(targetModule.get())
                && !compositionRoots.contains(originModule.get())
                && targetPackage.contains(APPLICATION_SEGMENT)
                && !targetPackage.contains(APPLICATION_CONTRACT_SEGMENT);
    }

    private static boolean isCrossModuleApplicationContractDependency(
            Dependency dependency,
            Set<String> modules,
            Set<String> compositionRoots
    ) {
        JavaClass origin = dependency.getOriginClass();
        JavaClass target = dependency.getTargetClass();
        Optional<String> originModule = topLevelModule(origin.getPackageName(), modules);
        Optional<String> targetModule = topLevelModule(target.getPackageName(), modules);

        return originModule.isPresent()
                && targetModule.isPresent()
                && !originModule.get().equals(targetModule.get())
                && !compositionRoots.contains(originModule.get())
                && isApplicationContractPackage(target.getPackageName(), targetModule.get());
    }

    private static boolean isApplicationContractPackage(String packageName, String module) {
        String contractPackage = ROOT_PREFIX + module + APPLICATION_CONTRACT_SEGMENT;
        return packageName.equals(contractPackage) || packageName.startsWith(contractPackage + ".");
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

    private static Optional<String> topLevelModule(String packageName, Set<String> modules) {
        return topLevelModule(packageName).filter(modules::contains);
    }

    private static final class TopLevelModulesAcyclicRule implements ArchRule {

        private final Set<String> modules;
        private final Set<String> compositionRoots;
        private final String description;

        private TopLevelModulesAcyclicRule(
                Set<String> modules,
                Set<String> compositionRoots,
                String description
        ) {
            this.modules = modules;
            this.compositionRoots = compositionRoots;
            this.description = description;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public EvaluationResult evaluate(JavaClasses classes) {
            Map<String, Set<String>> graph = buildContractGraph(classes);
            ConditionEvents events = ConditionEvents.Factory.create();
            for (Set<String> component : stronglyConnectedComponents(graph)) {
                if (component.size() < 2) {
                    continue;
                }
                List<String> cycle = findCycle(graph, component);
                events.add(SimpleConditionEvent.violated(
                        cycle,
                        "top-level module cycle: " + String.join(" -> ", cycle)
                ));
            }
            return new EvaluationResult(this, events, Priority.MEDIUM);
        }

        @Override
        public void check(JavaClasses classes) {
            ArchRule.Assertions.check(this, classes);
        }

        @Override
        public ArchRule as(String newDescription) {
            return new TopLevelModulesAcyclicRule(modules, compositionRoots, newDescription);
        }

        @Override
        public ArchRule because(String reason) {
            return new TopLevelModulesAcyclicRule(
                    modules,
                    compositionRoots,
                    description + ", because " + reason
            );
        }

        @Override
        public ArchRule allowEmptyShould(boolean allowEmptyShould) {
            return this;
        }

        private Map<String, Set<String>> buildContractGraph(JavaClasses classes) {
            Map<String, Set<String>> graph = new TreeMap<>();
            modules.forEach(module -> graph.put(module, new TreeSet<>()));
            for (JavaClass javaClass : classes) {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    if (!isCrossModuleApplicationContractDependency(dependency, modules, compositionRoots)) {
                        continue;
                    }
                    String origin = topLevelModule(dependency.getOriginClass().getPackageName(), modules)
                            .orElseThrow();
                    String target = topLevelModule(dependency.getTargetClass().getPackageName(), modules)
                            .orElseThrow();
                    graph.get(origin).add(target);
                }
            }
            return graph;
        }

        private static List<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
            TarjanState state = new TarjanState(graph);
            for (String module : graph.keySet()) {
                if (!state.indexByModule.containsKey(module)) {
                    state.visit(module);
                }
            }
            return state.components;
        }

        private static List<String> findCycle(Map<String, Set<String>> graph, Set<String> component) {
            for (String start : new TreeSet<>(component)) {
                List<String> path = new ArrayList<>();
                path.add(start);
                if (findCycleFrom(start, start, graph, component, new HashSet<>(), path)) {
                    return List.copyOf(path);
                }
            }
            throw new IllegalStateException("strongly connected component did not contain a cycle: " + component);
        }

        private static boolean findCycleFrom(
                String start,
                String current,
                Map<String, Set<String>> graph,
                Set<String> component,
                Set<String> visited,
                List<String> path
        ) {
            visited.add(current);
            for (String target : graph.getOrDefault(current, Set.of())) {
                if (!component.contains(target)) {
                    continue;
                }
                if (target.equals(start)) {
                    path.add(start);
                    return true;
                }
                if (visited.contains(target)) {
                    continue;
                }
                path.add(target);
                if (findCycleFrom(start, target, graph, component, visited, path)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
            visited.remove(current);
            return false;
        }
    }

    private static final class TarjanState {

        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> indexByModule = new HashMap<>();
        private final Map<String, Integer> lowLinkByModule = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> modulesOnStack = new HashSet<>();
        private final List<Set<String>> components = new ArrayList<>();
        private int nextIndex;

        private TarjanState(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private void visit(String module) {
            indexByModule.put(module, nextIndex);
            lowLinkByModule.put(module, nextIndex);
            nextIndex++;
            stack.push(module);
            modulesOnStack.add(module);

            for (String target : graph.getOrDefault(module, Set.of())) {
                if (!indexByModule.containsKey(target)) {
                    visit(target);
                    lowLinkByModule.put(
                            module,
                            Math.min(lowLinkByModule.get(module), lowLinkByModule.get(target))
                    );
                } else if (modulesOnStack.contains(target)) {
                    lowLinkByModule.put(
                            module,
                            Math.min(lowLinkByModule.get(module), indexByModule.get(target))
                    );
                }
            }

            if (!lowLinkByModule.get(module).equals(indexByModule.get(module))) {
                return;
            }

            Set<String> component = new TreeSet<>();
            String member;
            do {
                member = stack.pop();
                modulesOnStack.remove(member);
                component.add(member);
            } while (!member.equals(module));
            components.add(Set.copyOf(component));
        }
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

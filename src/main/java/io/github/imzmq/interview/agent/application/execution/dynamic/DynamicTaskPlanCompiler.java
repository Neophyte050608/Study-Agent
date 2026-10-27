package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DynamicTaskPlanCompiler {
    private final DynamicCapabilityRegistry registry;

    public DynamicTaskPlanCompiler(DynamicCapabilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Dynamic capability registry is required");
    }

    public List<DagNode> compile(
            ValidatedDynamicTaskPlan plan,
            DynamicExecutionContext context) {
        Objects.requireNonNull(plan, "Validated plan is required");
        Objects.requireNonNull(context, "Dynamic execution context is required");
        try {
            List<PreboundNode<?>> prebound = new ArrayList<>();
            for (ValidatedDynamicTaskNode node : plan.nodes()) {
                DynamicNodeCapability<?> capability = registry.lookup(
                        node.capabilityId(), node.capabilityVersion());
                prebound.add(prebind(node, capability));
            }

            List<DagNode> compiled = new ArrayList<>();
            for (PreboundNode<?> node : prebound) {
                compiled.add(toDagNode(node, context));
            }
            return List.copyOf(compiled);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("PLAN_COMPILE_FAILED");
        }
    }

    private static <A> PreboundNode<A> prebind(
            ValidatedDynamicTaskNode node,
            DynamicNodeCapability<A> capability) {
        A arguments = capability.decodeArguments(node.arguments());
        return new PreboundNode<>(node, capability, arguments);
    }

    private static <A> DagNode toDagNode(
            PreboundNode<A> prebound,
            DynamicExecutionContext context) {
        ValidatedDynamicTaskNode node = prebound.node();
        return new DagNode(
                node.id(),
                Set.copyOf(node.dependsOn()),
                prebound.capability().descriptor().requireSuccessfulDependencies(),
                upstream -> prebound.capability().execute(
                        context, Map.copyOf(upstream), prebound.arguments()));
    }

    private record PreboundNode<A>(
            ValidatedDynamicTaskNode node,
            DynamicNodeCapability<A> capability,
            A arguments) {
    }
}

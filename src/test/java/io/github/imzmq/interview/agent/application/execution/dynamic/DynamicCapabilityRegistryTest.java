package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicCapabilityRegistryTest {

    @Test
    void rejectsDuplicateCapabilityIdAndVersion() {
        DynamicNodeCapability<Void> first = capability("source.read", 1);
        DynamicNodeCapability<Void> duplicate = capability("source.read", 1);

        assertThatThrownBy(() -> new DynamicCapabilityRegistry(List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesExactVersionsAndExposesAnImmutableDescriptorSnapshot() {
        DynamicNodeCapability<Void> first = capability("source.read", 1);
        DynamicNodeCapability<Void> upgraded = capability("source.read", 2);
        DynamicCapabilityRegistry registry = new DynamicCapabilityRegistry(List.of(first, upgraded));

        assertThat(registry.lookup("source.read", 1)).isSameAs(first);
        assertThat(registry.lookup("source.read", 2)).isSameAs(upgraded);
        assertThat(registry.descriptors()).extracting(DynamicCapabilityDescriptor::capabilityVersion)
                .containsExactly(1, 2);
        assertThatThrownBy(() -> registry.descriptors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsUnknownCapabilityWithoutLeakingImplementationTypes() {
        DynamicCapabilityRegistry registry = new DynamicCapabilityRegistry(List.of());

        assertThatThrownBy(() -> registry.lookup("missing", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNKNOWN_CAPABILITY")
                .hasMessageNotContaining("DynamicCapabilityRegistry")
                .hasMessageNotContaining("CapabilityKey");
    }

    private static DynamicNodeCapability<Void> capability(String id, int version) {
        return new DynamicNodeCapability<>() {
            private final DynamicCapabilityDescriptor descriptor = new DynamicCapabilityDescriptor(
                    id, version, DynamicEffectType.READ_ONLY, Map.of(), Set.of(), 1, true);

            @Override
            public DynamicCapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Void decodeArguments(Map<String, Object> normalizedArguments) {
                return null;
            }

            @Override
            public Object execute(
                    DynamicExecutionContext context,
                    Map<String, DagNodeResult> upstream,
                    Void arguments) {
                return "ok";
            }
        };
    }
}

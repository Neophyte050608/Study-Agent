package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class DynamicCapabilityRegistry {
    private final Map<CapabilityKey, DynamicNodeCapability<?>> capabilities;
    private final List<DynamicCapabilityDescriptor> currentDescriptors;

    public DynamicCapabilityRegistry(Collection<DynamicNodeCapability<?>> capabilities) {
        Objects.requireNonNull(capabilities, "Dynamic capabilities are required");
        LinkedHashMap<CapabilityKey, DynamicNodeCapability<?>> registered = new LinkedHashMap<>();
        TreeMap<String, DynamicCapabilityDescriptor> currentById = new TreeMap<>();
        for (DynamicNodeCapability<?> capability : capabilities) {
            Objects.requireNonNull(capability, "Dynamic capability is required");
            DynamicCapabilityDescriptor descriptor = Objects.requireNonNull(
                    capability.descriptor(), "Dynamic capability descriptor is required");
            CapabilityKey key = new CapabilityKey(
                    descriptor.capabilityId(), descriptor.capabilityVersion());
            if (registered.putIfAbsent(key, capability) != null) {
                throw new IllegalArgumentException("Duplicate dynamic capability version");
            }
            currentById.merge(descriptor.capabilityId(), descriptor,
                    (left, right) -> left.capabilityVersion() > right.capabilityVersion()
                            ? left : right);
        }
        this.capabilities = Collections.unmodifiableMap(registered);
        this.currentDescriptors = List.copyOf(currentById.values());
    }

    public List<DynamicCapabilityDescriptor> descriptors() {
        return currentDescriptors;
    }

    DynamicNodeCapability<?> lookup(String capabilityId, int capabilityVersion) {
        DynamicNodeCapability<?> capability = capabilities.get(
                new CapabilityKey(capabilityId, capabilityVersion));
        if (capability == null) {
            throw new IllegalArgumentException("UNKNOWN_CAPABILITY");
        }
        return capability;
    }

    private record CapabilityKey(String capabilityId, int capabilityVersion) {
    }
}

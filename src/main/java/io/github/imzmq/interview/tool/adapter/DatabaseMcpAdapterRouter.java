package io.github.imzmq.interview.tool.adapter;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.imzmq.interview.tool.gateway.McpCapabilityGateway;
import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;

@Component
public class DatabaseMcpAdapterRouter implements McpCapabilityGateway {
    private final List<DatabaseMcpAdapter> adapters;

    public DatabaseMcpAdapterRouter(List<DatabaseMcpAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : adapters;
    }

    @Override
    public List<String> listCapabilities() {
        Set<String> merged = new LinkedHashSet<>();
        for (DatabaseMcpAdapter adapter : adapters) {
            if (adapter == null || adapter.capabilities() == null) {
                continue;
            }
            merged.addAll(adapter.capabilities());
        }
        return merged.stream().toList();
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params) {
        return invokeCapability(name, params, Map.of());
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params, Map<String, Object> context) {
        DatabaseMcpAdapter adapter = adapterOf(name);
        if (adapter == null) {
            throw new BusinessException(ErrorCode.MCP_INVALID_PARAMS, "database capability is not supported");
        }
        return adapter.invoke(name, params == null ? Map.of() : params, context == null ? Map.of() : context);
    }

    public boolean supports(String capability) {
        return adapterOf(capability) != null;
    }

    private DatabaseMcpAdapter adapterOf(String capability) {
        for (DatabaseMcpAdapter adapter : adapters) {
            if (adapter != null && adapter.supports(capability)) {
                return adapter;
            }
        }
        return null;
    }
}


package io.github.imzmq.interview.im.application.runtime;

import io.github.imzmq.interview.im.domain.UnifiedReply;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImPlatformAdapterRegistry {

    private final List<ImPlatformAdapter> adapters;

    public ImPlatformAdapterRegistry(List<ImPlatformAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    public void sendReply(UnifiedReply reply) {
        String platform = reply == null ? "" : reply.getPlatform();
        for (ImPlatformAdapter adapter : adapters) {
            if (adapter != null && adapter.supports(platform)) {
                adapter.sendReply(reply);
                return;
            }
        }
    }
}




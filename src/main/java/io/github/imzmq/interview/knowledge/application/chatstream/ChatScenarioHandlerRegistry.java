package io.github.imzmq.interview.knowledge.application.chatstream;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatScenarioHandlerRegistry {

    private final List<ChatScenarioHandler> handlers;

    public ChatScenarioHandlerRegistry(List<ChatScenarioHandler> handlers) {
        if (handlers == null) {
            this.handlers = List.of();
            return;
        }
        List<ChatScenarioHandler> ordered = new ArrayList<>(handlers);
        AnnotationAwareOrderComparator.sort(ordered);
        this.handlers = List.copyOf(ordered);
    }

    public boolean handle(StreamingChatContext context) {
        for (ChatScenarioHandler handler : handlers) {
            if (handler != null && handler.handle(context)) {
                return true;
            }
        }
        return false;
    }
}



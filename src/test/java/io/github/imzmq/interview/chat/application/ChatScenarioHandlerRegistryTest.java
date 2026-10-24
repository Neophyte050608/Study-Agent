package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.chat.application.contract.extension.ChatScenarioHandler;
import io.github.imzmq.interview.chat.application.contract.extension.StreamingChatContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatScenarioHandlerRegistryTest {

    @Test
    void honorsSpringOrderAndStopsAfterFirstHandlerAcceptsContext() {
        List<String> calls = new ArrayList<>();
        ChatScenarioHandler first = orderedHandler(10, false, "first", calls);
        ChatScenarioHandler accepting = orderedHandler(20, true, "accepting", calls);
        ChatScenarioHandler never = orderedHandler(30, true, "never", calls);
        ChatScenarioHandlerRegistry registry = new ChatScenarioHandlerRegistry(List.of(accepting, never, first));

        boolean handled = registry.handle(null);

        assertThat(handled).isTrue();
        assertThat(calls).containsExactly("first", "accepting");
    }

    private ChatScenarioHandler orderedHandler(int order, boolean result, String name, List<String> calls) {
        return new OrderedHandler(order, result, name, calls);
    }

    private record OrderedHandler(int order, boolean result, String name, List<String> calls)
            implements ChatScenarioHandler, Ordered {
        @Override
        public boolean handle(StreamingChatContext context) {
            calls.add(name);
            return result;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}

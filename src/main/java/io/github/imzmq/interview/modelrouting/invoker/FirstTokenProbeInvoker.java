package io.github.imzmq.interview.modelrouting.invoker;

import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.modelrouting.probe.ModelProbeAwaiter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class FirstTokenProbeInvoker {

    private final ModelProbeAwaiter modelProbeAwaiter;

    public FirstTokenProbeInvoker(ModelProbeAwaiter modelProbeAwaiter) {
        this.modelProbeAwaiter = modelProbeAwaiter;
    }

    public String invoke(ChatModel chatModel, String systemPrompt, String userPrompt, TimeoutHint hint) {
        var builder = ChatClient.builder(chatModel).build().prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.system(systemPrompt);
        }
        Flux<String> tokenFlux = builder.user(userPrompt).stream().content();
        return modelProbeAwaiter.awaitFirstToken(tokenFlux, hint);
    }
}




package io.github.imzmq.interview.modelrouting.invoker;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

@Component
public class StreamingChatInvoker {

    public String invoke(ChatModel chatModel, String systemPrompt, String userPrompt, Consumer<String> tokenConsumer) {
        var builder = ChatClient.builder(chatModel).build().prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.system(systemPrompt);
        }
        Flux<String> tokenFlux = builder.user(userPrompt).stream().content();

        List<String> tokens = tokenFlux
                .doOnNext(token -> {
                    if (token != null && !token.isEmpty()) {
                        tokenConsumer.accept(token);
                    }
                })
                .collectList()
                .block();

        if (tokens == null || tokens.isEmpty()) {
            throw new ModelRoutingException("模型流式返回为空");
        }
        return String.join("", tokens);
    }
}




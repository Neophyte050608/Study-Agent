package io.github.imzmq.interview.modelrouting.invoker;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingException;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

@Component
public class MetadataChatInvoker {

    public RoutingChatService.RoutingResult invoke(ChatModel chatModel, String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        var builder = ChatClient.builder(chatModel).build().prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.system(systemPrompt);
        }
        ChatResponse response = builder.user(userPrompt).call().chatResponse();

        long cost = System.currentTimeMillis() - start;
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ModelRoutingException("模型返回为空");
        }

        String content = response.getResult().getOutput().getText();
        int inputTokens = 0;
        int outputTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            inputTokens = response.getMetadata().getUsage().getPromptTokens() != null
                    ? response.getMetadata().getUsage().getPromptTokens().intValue() : 0;
            outputTokens = response.getMetadata().getUsage().getCompletionTokens() != null
                    ? response.getMetadata().getUsage().getCompletionTokens().intValue() : 0;
        }

        return new RoutingChatService.RoutingResult(content, inputTokens, outputTokens, cost);
    }
}




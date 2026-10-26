package io.github.imzmq.interview.chat.application.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.imzmq.interview.chat.application.contract.PromptRenderer;
import io.github.imzmq.interview.chat.application.contract.result.RenderedPrompt;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputParser;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputResult;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputSchema;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChatMemoryExtractionService {

    private static final StructuredOutputSchema RESPONSE_SCHEMA = StructuredOutputSchema.builder()
            .required("memories")
            .type("memories", StructuredOutputSchema.JsonType.ARRAY, List.of())
            .build();

    private final RoutingChatService routingChatService;
    private final PromptRenderer promptRenderer;
    private final StructuredOutputParser parser;
    private final ChatMemoryCandidateValidator validator;
    private final ChatMemoryCommitService commitService;
    private final ChatMemoryProperties properties;

    public ChatMemoryExtractionService(RoutingChatService routingChatService,
                                       PromptRenderer promptRenderer,
                                       StructuredOutputParser parser,
                                       ChatMemoryCandidateValidator validator,
                                       ChatMemoryCommitService commitService,
                                       ChatMemoryProperties properties) {
        this.routingChatService = routingChatService;
        this.promptRenderer = promptRenderer;
        this.parser = parser;
        this.validator = validator;
        this.commitService = commitService;
        this.properties = properties;
    }

    public void extractAndCommit(ChatMemoryExtractionTaskDO task,
                                 List<ChatMemorySourceMessage> sourceMessages) {
        extractAndCommitBatch(List.of(task), sourceMessages);
    }

    public void extractAndCommitBatch(List<ChatMemoryExtractionTaskDO> tasks,
                                      List<ChatMemorySourceMessage> sourceMessages) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        ChatMemoryExtractionTaskDO first = tasks.getFirst();
        Set<String> allowedSourceIds = new java.util.LinkedHashSet<>();
        for (ChatMemoryExtractionTaskDO task : tasks) {
            if (task == null || task.getLeaseToken() == null || task.getLeaseToken().isBlank()) {
                throw new ChatMemoryExtractionException("MEMORY_LEASE_MISSING");
            }
            if (!first.getUserId().equals(task.getUserId())
                    || !first.getSessionId().equals(task.getSessionId())) {
                throw new ChatMemoryExtractionException("MEMORY_TASK_BATCH_INCOMPATIBLE");
            }
            allowedSourceIds.add(task.getSourceMessageId());
        }

        List<Map<String, Object>> promptMessages = boundedPromptMessages(
                sourceMessages, allowedSourceIds);
        RenderedPrompt prompt = promptRenderer.renderSplit(
                "chat-memory-extract-system",
                "chat-memory-extract-task",
                Map.of("messages", promptMessages));
        String raw = routingChatService.call(prompt.systemPrompt(), prompt.userPrompt(),
                ModelRouteType.GENERAL, "chat-memory-extract");
        StructuredOutputResult<JsonNode> parsed = parser.parseTree(raw, RESPONSE_SCHEMA, null);
        if (!parsed.success()) {
            throw new ChatMemoryExtractionException("MEMORY_PARSE_FAILED");
        }

        List<ChatMemoryCandidate> candidates = validator.validate(
                parsed.data(), first.getUserId(), first.getSessionId(),
                allowedSourceIds, sourceMessages);
        Map<String, List<ChatMemoryCandidate>> candidatesBySource = candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        candidate -> candidate.source().sourceMessageId()));
        List<ChatMemoryCommitRequest> requests = tasks.stream()
                .map(task -> new ChatMemoryCommitRequest(
                        task.getTaskId(), task.getLeaseToken(),
                        candidatesBySource.getOrDefault(task.getSourceMessageId(), List.of())))
                .toList();
        commitService.commitBatch(requests);
    }

    private List<Map<String, Object>> boundedPromptMessages(
            List<ChatMemorySourceMessage> sources,
            Set<String> claimedSourceIds) {
        int sourceCodePoints = sources.stream()
                .filter(source -> claimedSourceIds.contains(source.messageId()))
                .mapToInt(source -> codePoints(source.content()))
                .sum();
        if (sourceCodePoints > properties.getExtractionInputCodePoints()) {
            throw new ChatMemoryExtractionException("MEMORY_INPUT_TOO_LARGE");
        }

        int contextRemaining = properties.getExtractionInputCodePoints() - sourceCodePoints;
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMemorySourceMessage source : sources) {
            if (source.content() == null || source.content().isBlank()) {
                continue;
            }
            boolean claimed = claimedSourceIds.contains(source.messageId());
            int accepted = claimed
                    ? codePoints(source.content())
                    : Math.min(codePoints(source.content()), contextRemaining);
            if (accepted <= 0) {
                continue;
            }
            int end = source.content().offsetByCodePoints(0, accepted);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", source.messageId());
            message.put("role", source.role());
            message.put("content", source.content().substring(0, end));
            result.add(message);
            if (!claimed) {
                contextRemaining -= accepted;
            }
        }
        return List.copyOf(result);
    }

    private int codePoints(String content) {
        return content == null ? 0 : content.codePointCount(0, content.length());
    }

}

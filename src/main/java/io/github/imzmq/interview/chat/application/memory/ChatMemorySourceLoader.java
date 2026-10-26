package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ChatMemorySourceLoader {

    private static final int PRIOR_CONTEXT_LIMIT = 4;

    private final ChatMessageMapper messageMapper;

    public ChatMemorySourceLoader(ChatMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public List<ChatMemorySourceMessage> load(ChatMemoryExtractionTaskDO task) {
        return loadBatch(List.of(task));
    }

    public List<ChatMemorySourceMessage> loadBatch(List<ChatMemoryExtractionTaskDO> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        ChatMemoryExtractionTaskDO first = tasks.getFirst();
        if (tasks.stream().anyMatch(task -> !first.getUserId().equals(task.getUserId())
                || !first.getSessionId().equals(task.getSessionId()))) {
            throw new ChatMemoryExtractionException("MEMORY_TASK_BATCH_INCOMPATIBLE");
        }

        List<String> sourceIds = tasks.stream()
                .map(ChatMemoryExtractionTaskDO::getSourceMessageId)
                .toList();
        List<ChatMessageDO> sources = messageMapper.selectMemorySourceMessages(
                first.getUserId(), first.getSessionId(), sourceIds);
        validateSources(tasks, sources);

        long firstSourcePk = tasks.stream()
                .map(ChatMemoryExtractionTaskDO::getSourceMessagePk)
                .min(Long::compareTo)
                .orElseThrow();
        List<ChatMessageDO> context = messageMapper.selectMemoryPriorContext(
                first.getUserId(), first.getSessionId(), firstSourcePk, PRIOR_CONTEXT_LIMIT);

        List<ChatMessageDO> chronological = new ArrayList<>(context.size() + sources.size());
        chronological.addAll(context);
        chronological.addAll(sources);
        chronological.sort(Comparator.comparing(ChatMessageDO::getId));
        return chronological.stream()
                .map(message -> toSource(first.getUserId(), message))
                .toList();
    }

    private void validateSources(List<ChatMemoryExtractionTaskDO> tasks, List<ChatMessageDO> sources) {
        Set<String> expectedIds = new HashSet<>();
        for (ChatMemoryExtractionTaskDO task : tasks) {
            expectedIds.add(task.getSourceMessageId());
        }
        Set<String> loadedIds = new HashSet<>();
        for (ChatMessageDO source : sources) {
            if (!"user".equals(source.getRole()) || Boolean.TRUE.equals(source.getDeleted())) {
                throw new ChatMemoryExtractionException("MEMORY_SOURCE_INVALID");
            }
            loadedIds.add(source.getMessageId());
        }
        if (!loadedIds.equals(expectedIds)) {
            throw new ChatMemoryExtractionException("MEMORY_SOURCE_INVALID");
        }
    }

    private ChatMemorySourceMessage toSource(String userId, ChatMessageDO message) {
        return new ChatMemorySourceMessage(
                message.getId(), message.getMessageId(), userId, message.getSessionId(),
                message.getRole(), Boolean.TRUE.equals(message.getDeleted()), message.getContent());
    }
}

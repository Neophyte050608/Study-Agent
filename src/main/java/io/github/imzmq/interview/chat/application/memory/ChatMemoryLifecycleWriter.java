package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryRelationType;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryStatus;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ChatMemoryLifecycleWriter {

    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryEvolutionMapper evolutionMapper;
    private final ChatMemoryOutboxEventService outboxEventService;
    private final ChatMemoryLifecyclePolicy policy = new ChatMemoryLifecyclePolicy();

    public ChatMemoryLifecycleWriter(
            ChatMemoryItemMapper itemMapper,
            ChatMemorySourceMapper sourceMapper,
            ChatMemoryEvolutionMapper evolutionMapper,
            ChatMemoryOutboxEventService outboxEventService) {
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.evolutionMapper = evolutionMapper;
        this.outboxEventService = outboxEventService;
    }

    public void writeBatch(List<ChatMemoryLifecycleRequest> requests, LocalDateTime writeTime) {
        Objects.requireNonNull(writeTime, "writeTime");
        if (requests == null || requests.isEmpty()) {
            return;
        }

        Map<ThemeKey, List<ChatMemoryLifecycleRequest>> groups = groupRequests(requests);
        Map<ThemeKey, Boolean> ambiguity = detectAmbiguity(groups);
        groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> writeGroup(
                        entry.getKey(), entry.getValue(), ambiguity.get(entry.getKey()), writeTime));
    }

    private Map<ThemeKey, List<ChatMemoryLifecycleRequest>> groupRequests(
            List<ChatMemoryLifecycleRequest> requests) {
        Map<ThemeKey, List<ChatMemoryLifecycleRequest>> groups = new LinkedHashMap<>();
        for (ChatMemoryLifecycleRequest request : requests) {
            validateRequest(request);
            ThemeKey key = new ThemeKey(
                    request.userId(),
                    request.candidate().category().name(),
                    request.candidate().subjectKey().value());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(request);
        }
        groups.values().forEach(group -> group.sort(Comparator
                .comparing(ChatMemoryLifecycleRequest::originMessageCreatedAt)
                .thenComparingLong(ChatMemoryLifecycleRequest::originMessagePk)
                .thenComparingInt(ChatMemoryLifecycleRequest::originQuoteStart)
                .thenComparing(ChatMemoryLifecycleRequest::contentHash)));
        return groups;
    }

    private Map<ThemeKey, Boolean> detectAmbiguity(
            Map<ThemeKey, List<ChatMemoryLifecycleRequest>> groups) {
        Map<ThemeKey, Boolean> ambiguity = new LinkedHashMap<>();
        groups.forEach((key, group) -> {
            boolean ambiguous = group.stream().anyMatch(ChatMemoryLifecycleRequest::ambiguousBatch)
                    || group.getFirst().cardinality() == ChatMemoryCardinality.SINGLE
                    && group.stream().map(ChatMemoryLifecycleRequest::contentHash).distinct().count() > 1;
            ambiguity.put(key, ambiguous);
        });
        return ambiguity;
    }

    private void writeGroup(
            ThemeKey key,
            List<ChatMemoryLifecycleRequest> requests,
            boolean ambiguous,
            LocalDateTime writeTime) {
        List<ChatMemoryItemDO> current = new ArrayList<>(itemMapper.lockThemeRows(
                key.userId(), key.category(), key.subjectKey()));
        for (ChatMemoryLifecycleRequest original : requests) {
            ChatMemoryLifecycleRequest request = withAmbiguity(original, ambiguous);
            ChatMemoryLifecyclePolicy.Decision decision = policy.decide(
                    request, current.stream().map(this::toCurrentItem).toList());
            apply(request, decision, current, writeTime);
        }
    }

    private void apply(
            ChatMemoryLifecycleRequest request,
            ChatMemoryLifecyclePolicy.Decision decision,
            List<ChatMemoryItemDO> current,
            LocalDateTime writeTime) {
        switch (decision.action()) {
            case ACTIVATE -> activate(request, current, writeTime);
            case ATTACH_SOURCE -> attachSource(
                    request, findRelated(current, decision.relatedMemoryIds().getFirst()), writeTime);
            case SUPERSEDE -> supersede(request, decision, current, writeTime);
            case QUARANTINE -> quarantine(request, decision, current, writeTime);
        }
    }

    private void activate(
            ChatMemoryLifecycleRequest request,
            List<ChatMemoryItemDO> current,
            LocalDateTime writeTime) {
        ChatMemoryItemDO item = insertItem(
                request, ChatMemoryStatus.ACTIVE, null, writeTime);
        current.add(item);
        outboxEventService.enqueue(item.getId(), request.userId(), 1L, "UPSERT");
    }

    private void attachSource(
            ChatMemoryLifecycleRequest request,
            ChatMemoryItemDO item,
            LocalDateTime writeTime) {
        ChatMemorySourceDO source = lockExactSource(item.getId(), request);
        if (source != null && source.getInvalidatedAt() == null) {
            return;
        }
        if (source == null) {
            insertSource(item.getId(), request, writeTime);
        } else {
            source.setInvalidatedAt(null);
            requireWrite(sourceMapper.updateById(source));
        }

        long currentVersion = item.getVersion();
        long nextVersion = currentVersion + 1;
        requireVersionWrite(itemMapper.bumpVersion(
                item.getId(), request.userId(), currentVersion, nextVersion));
        item.setVersion(nextVersion);
        outboxEventService.enqueue(item.getId(), request.userId(), nextVersion, "UPSERT");
    }

    private void supersede(
            ChatMemoryLifecycleRequest request,
            ChatMemoryLifecyclePolicy.Decision decision,
            List<ChatMemoryItemDO> current,
            LocalDateTime writeTime) {
        ChatMemoryItemDO oldItem = findRelated(current, decision.relatedMemoryIds().getFirst());
        long nextVersion = oldItem.getVersion() + 1;
        requireVersionWrite(itemMapper.markSuperseded(
                oldItem.getId(), request.userId(), oldItem.getVersion(), nextVersion,
                request.originMessageCreatedAt()));
        oldItem.setStatus(ChatMemoryStatus.SUPERSEDED.name());
        oldItem.setValidUntil(request.originMessageCreatedAt());
        oldItem.setVersion(nextVersion);

        ChatMemoryItemDO newItem = insertItem(
                request, ChatMemoryStatus.ACTIVE, null, writeTime);
        current.add(newItem);
        insertRelation(request.userId(), newItem.getId(), oldItem.getId(),
                ChatMemoryRelationType.SUPERSEDES, writeTime);
        outboxEventService.enqueue(oldItem.getId(), request.userId(), nextVersion, "DELETE");
        outboxEventService.enqueue(newItem.getId(), request.userId(), 1L, "UPSERT");
    }

    private void quarantine(
            ChatMemoryLifecycleRequest request,
            ChatMemoryLifecyclePolicy.Decision decision,
            List<ChatMemoryItemDO> current,
            LocalDateTime writeTime) {
        ChatMemoryItemDO item = insertItem(
                request, ChatMemoryStatus.QUARANTINED,
                decision.quarantineReason().name(), writeTime);
        current.add(item);
        for (Long relatedId : decision.relatedMemoryIds()) {
            findRelated(current, relatedId);
            insertRelation(request.userId(), item.getId(), relatedId,
                    ChatMemoryRelationType.CONFLICTS_WITH, writeTime);
        }
    }

    private ChatMemoryItemDO insertItem(
            ChatMemoryLifecycleRequest request,
            ChatMemoryStatus status,
            String quarantineReason,
            LocalDateTime writeTime) {
        ChatMemoryItemDO item = new ChatMemoryItemDO();
        item.setUserId(request.userId());
        item.setCategory(request.candidate().category().name());
        item.setSubjectKey(request.candidate().subjectKey().value());
        item.setSubjectCardinality(request.cardinality().name());
        item.setContent(request.candidate().content());
        item.setContentHash(request.contentHash());
        item.setStatus(status.name());
        item.setVersion(1L);
        item.setValidFrom(request.originMessageCreatedAt());
        item.setOriginMessageCreatedAt(request.originMessageCreatedAt());
        item.setOriginMessagePk(request.originMessagePk());
        item.setOriginQuoteStart(request.originQuoteStart());
        item.setQuarantineReason(quarantineReason);
        item.setCreatedAt(writeTime);
        item.setUpdatedAt(writeTime);
        requireWrite(itemMapper.insert(item));
        if (item.getId() == null) {
            throw new ChatMemoryCommitException("MEMORY_WRITE_CONFLICT");
        }
        insertSource(item.getId(), request, writeTime);
        return item;
    }

    private ChatMemorySourceDO lockExactSource(
            long memoryId,
            ChatMemoryLifecycleRequest request) {
        return sourceMapper.selectOne(new LambdaQueryWrapper<ChatMemorySourceDO>()
                .eq(ChatMemorySourceDO::getMemoryId, memoryId)
                .eq(ChatMemorySourceDO::getUserId, request.userId())
                .eq(ChatMemorySourceDO::getSourceMessageId,
                        request.candidate().source().sourceMessageId())
                .eq(ChatMemorySourceDO::getSourceMessagePk, request.originMessagePk())
                .eq(ChatMemorySourceDO::getSourceStart, request.candidate().source().start())
                .eq(ChatMemorySourceDO::getSourceEnd, request.candidate().source().end())
                .last("LIMIT 1 FOR UPDATE"));
    }

    private void insertSource(
            long memoryId,
            ChatMemoryLifecycleRequest request,
            LocalDateTime writeTime) {
        ChatMemorySourceDO source = new ChatMemorySourceDO();
        source.setMemoryId(memoryId);
        source.setUserId(request.userId());
        source.setSessionId(request.sessionId());
        source.setSourceMessageId(request.candidate().source().sourceMessageId());
        source.setSourceMessagePk(request.originMessagePk());
        source.setSourceStart(request.candidate().source().start());
        source.setSourceEnd(request.candidate().source().end());
        source.setCreatedAt(writeTime);
        requireWrite(sourceMapper.insert(source));
    }

    private void insertRelation(
            String userId,
            long fromMemoryId,
            long toMemoryId,
            ChatMemoryRelationType type,
            LocalDateTime writeTime) {
        ChatMemoryEvolutionDO relation = new ChatMemoryEvolutionDO();
        relation.setUserId(userId);
        relation.setFromMemoryId(fromMemoryId);
        relation.setToMemoryId(toMemoryId);
        relation.setRelationType(type.name());
        relation.setCreatedAt(writeTime);
        requireWrite(evolutionMapper.insert(relation));
    }

    private ChatMemoryLifecyclePolicy.CurrentItem toCurrentItem(ChatMemoryItemDO item) {
        try {
            return new ChatMemoryLifecyclePolicy.CurrentItem(
                    item.getId(), item.getContentHash(), ChatMemoryStatus.valueOf(item.getStatus()),
                    ChatMemoryCardinality.valueOf(item.getSubjectCardinality()),
                    item.getOriginMessageCreatedAt(), item.getOriginMessagePk(),
                    item.getOriginQuoteStart(), item.getVersion());
        } catch (RuntimeException exception) {
            throw new ChatMemoryCommitException("MEMORY_LIFECYCLE_STATE_INVALID");
        }
    }

    private ChatMemoryItemDO findRelated(List<ChatMemoryItemDO> current, long id) {
        return current.stream()
                .filter(item -> Objects.equals(item.getId(), id))
                .findFirst()
                .orElseThrow(() -> new ChatMemoryCommitException("MEMORY_LIFECYCLE_STATE_INVALID"));
    }

    private ChatMemoryLifecycleRequest withAmbiguity(
            ChatMemoryLifecycleRequest request,
            boolean ambiguous) {
        if (request.ambiguousBatch() == ambiguous) {
            return request;
        }
        return new ChatMemoryLifecycleRequest(
                request.userId(), request.sessionId(), request.candidate(), request.contentHash(),
                request.cardinality(), request.originMessageCreatedAt(), request.originMessagePk(),
                request.originQuoteStart(), ambiguous);
    }

    private void validateRequest(ChatMemoryLifecycleRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()
                || request.sessionId() == null || request.sessionId().isBlank()
                || request.candidate() == null || request.contentHash() == null
                || request.contentHash().isBlank() || request.cardinality() == null
                || request.originMessageCreatedAt() == null || request.originMessagePk() <= 0
                || request.originQuoteStart() != request.candidate().source().start()) {
            throw new ChatMemoryCommitException("MEMORY_LIFECYCLE_REQUEST_INVALID");
        }
    }

    private void requireWrite(int affectedRows) {
        if (affectedRows != 1) {
            throw new ChatMemoryCommitException("MEMORY_WRITE_CONFLICT");
        }
    }

    private void requireVersionWrite(int affectedRows) {
        if (affectedRows != 1) {
            throw new ChatMemoryCommitException("MEMORY_VERSION_CONFLICT");
        }
    }

    private record ThemeKey(String userId, String category, String subjectKey)
            implements Comparable<ThemeKey> {
        @Override
        public int compareTo(ThemeKey other) {
            int user = userId.compareTo(other.userId);
            if (user != 0) {
                return user;
            }
            int categoryOrder = category.compareTo(other.category);
            return categoryOrder != 0 ? categoryOrder : subjectKey.compareTo(other.subjectKey);
        }
    }
}

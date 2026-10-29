package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.application.contract.ChatMemoryOperations;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryRelationView;
import io.github.imzmq.interview.chat.application.contract.result.ChatMemoryView;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryQuarantineReason;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryRelationType;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryStatus;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryEvolutionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ChatMemoryManagementService implements ChatMemoryOperations {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String STATUS_INVALID = "MEMORY_STATUS_INVALID";
    private static final String NOT_FOUND = "MEMORY_NOT_FOUND";
    private static final String STATE_CONFLICT = "MEMORY_STATE_CONFLICT";
    private static final String CARDINALITY_CONFLICT = "MEMORY_CARDINALITY_CONFLICT";

    private static final Comparator<ChatMemoryItemDO> SOURCE_ORDER = Comparator
            .comparing(ChatMemoryItemDO::getOriginMessageCreatedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ChatMemoryItemDO::getOriginMessagePk,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ChatMemoryItemDO::getOriginQuoteStart,
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryEvolutionMapper evolutionMapper;
    private final ChatMemoryOutboxEventService outboxEventService;
    private final ChatMemoryObservationPublisher observationPublisher;
    private final Clock clock;

    public ChatMemoryManagementService(
            ChatMemoryItemMapper itemMapper,
            ChatMemorySourceMapper sourceMapper,
            ChatMemoryEvolutionMapper evolutionMapper,
            ChatMemoryOutboxEventService outboxEventService,
            ChatMemoryObservationPublisher observationPublisher,
            Clock clock) {
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.evolutionMapper = evolutionMapper;
        this.outboxEventService = outboxEventService;
        this.observationPublisher = observationPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMemoryView> list(
            String userId, String status, int limit, Long beforeId) {
        Objects.requireNonNull(userId, "userId");
        String validatedStatus = validateStatus(status);
        int pageSize = Math.max(MIN_PAGE_SIZE, Math.min(limit, MAX_PAGE_SIZE));
        List<ChatMemoryItemDO> items = itemMapper.selectManagementPage(
                userId, validatedStatus, beforeId, pageSize);
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> memoryIds = items.stream().map(ChatMemoryItemDO::getId).toList();
        Map<Long, List<String>> sourceIdsByMemory = groupSourceMessageIds(
                sourceMapper.selectAccessibleSources(userId, memoryIds));
        return items.stream()
                .map(item -> toView(item, sourceIdsByMemory.getOrDefault(item.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMemoryRelationView> evolution(String userId, long memoryId) {
        Objects.requireNonNull(userId, "userId");
        return evolutionMapper.selectOwnedRelations(userId, memoryId).stream()
                .map(relation -> new ChatMemoryRelationView(
                        relation.getFromMemoryId(),
                        relation.getToMemoryId(),
                        relation.getRelationType(),
                        toInstant(relation.getCreatedAt())))
                .toList();
    }

    @Override
    @Transactional
    public void confirmReplacement(String userId, long memoryId, long expectedVersion) {
        Objects.requireNonNull(userId, "userId");
        ChatMemoryItemDO discovered = itemMapper.selectOwnedItem(userId, memoryId);
        if (discovered == null) {
            throw failure(NOT_FOUND);
        }

        List<ChatMemoryItemDO> lockedTheme = itemMapper.lockThemeRows(
                userId, discovered.getCategory(), discovered.getSubjectKey());
        ChatMemoryItemDO candidate = lockedTheme.stream()
                .filter(item -> Objects.equals(item.getId(), memoryId))
                .findFirst()
                .orElseThrow(() -> failure(STATE_CONFLICT));
        requireQuarantinedVersion(candidate, expectedVersion);
        if (ChatMemoryQuarantineReason.OLDER_THAN_CURRENT.name()
                .equals(candidate.getQuarantineReason())) {
            throw failure(STATE_CONFLICT);
        }

        List<Long> targetIds = evolutionMapper.selectOwnedConflictTargets(userId, memoryId);
        Set<Long> targetIdSet = new HashSet<>(targetIds);
        Set<Long> currentActiveIds = lockedTheme.stream()
                .filter(item -> ChatMemoryStatus.ACTIVE.name().equals(item.getStatus()))
                .map(ChatMemoryItemDO::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (targetIds.isEmpty()
                || targetIdSet.size() != targetIds.size()
                || !currentActiveIds.equals(targetIdSet)) {
            throw failure(STATE_CONFLICT);
        }
        Map<Long, ChatMemoryItemDO> lockedById = new HashMap<>();
        for (ChatMemoryItemDO item : lockedTheme) {
            lockedById.put(item.getId(), item);
        }

        List<ChatMemoryItemDO> targets = new ArrayList<>();
        for (Long targetId : targetIds) {
            ChatMemoryItemDO target = lockedById.get(targetId);
            if (!isCurrentOlderConflict(userId, candidate, target)) {
                throw failure(STATE_CONFLICT);
            }
            targets.add(target);
        }

        long candidateNextVersion = candidate.getVersion() + 1;
        for (ChatMemoryItemDO target : targets) {
            long targetNextVersion = target.getVersion() + 1;
            requireWrite(itemMapper.markSuperseded(
                    target.getId(), userId, target.getVersion(), targetNextVersion,
                    candidate.getValidFrom()));
        }
        requireWrite(itemMapper.activateQuarantined(
                candidate.getId(), userId, candidate.getVersion(), candidateNextVersion));

        LocalDateTime relationTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (ChatMemoryItemDO target : targets) {
            requireWrite(evolutionMapper.insert(relation(
                    userId, candidate.getId(), target.getId(), relationTime)));
        }
        for (ChatMemoryItemDO target : targets) {
            outboxEventService.enqueue(
                    target.getId(), userId, target.getVersion() + 1, "DELETE");
        }
        outboxEventService.enqueue(candidate.getId(), userId, candidateNextVersion, "UPSERT");
        observationPublisher.lifecycleAfterCommit("CONFIRMED_REPLACEMENT", Map.of(
                "stage", "management",
                "resultCount", targets.size() + 1,
                "previousStatus", ChatMemoryStatus.QUARANTINED.name(),
                "nextStatus", ChatMemoryStatus.ACTIVE.name()));
    }

    @Override
    @Transactional
    public void confirmCoexistence(String userId, long memoryId, long expectedVersion) {
        Objects.requireNonNull(userId, "userId");
        ChatMemoryItemDO candidate = requireOwnedLocked(userId, memoryId);
        requireQuarantinedVersion(candidate, expectedVersion);
        if (!ChatMemoryCardinality.MULTI.name().equals(candidate.getSubjectCardinality())) {
            throw failure(CARDINALITY_CONFLICT);
        }

        long nextVersion = candidate.getVersion() + 1;
        requireWrite(itemMapper.activateQuarantined(
                memoryId, userId, candidate.getVersion(), nextVersion));
        outboxEventService.enqueue(memoryId, userId, nextVersion, "UPSERT");
        observationPublisher.lifecycleAfterCommit("CONFIRMED_COEXISTENCE", Map.of(
                "stage", "management",
                "resultCount", 1,
                "previousStatus", ChatMemoryStatus.QUARANTINED.name(),
                "nextStatus", ChatMemoryStatus.ACTIVE.name()));
    }

    @Override
    @Transactional
    public void delete(String userId, long memoryId, long expectedVersion) {
        Objects.requireNonNull(userId, "userId");
        ChatMemoryItemDO item = requireOwnedLocked(userId, memoryId);
        if (item.getVersion() != expectedVersion
                || !(ChatMemoryStatus.ACTIVE.name().equals(item.getStatus())
                || ChatMemoryStatus.QUARANTINED.name().equals(item.getStatus()))) {
            throw failure(STATE_CONFLICT);
        }

        long nextVersion = item.getVersion() + 1;
        LocalDateTime deletedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        requireWrite(itemMapper.markDeleted(
                memoryId, userId, item.getStatus(), item.getVersion(), nextVersion, deletedAt));
        if (ChatMemoryStatus.ACTIVE.name().equals(item.getStatus())) {
            outboxEventService.enqueue(memoryId, userId, nextVersion, "DELETE");
        }
        observationPublisher.lifecycleAfterCommit("DELETED", Map.of(
                "stage", "management",
                "resultCount", 1,
                "previousStatus", item.getStatus(),
                "nextStatus", ChatMemoryStatus.DELETED.name()));
    }

    private String validateStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ChatMemoryStatus.valueOf(status).name();
        } catch (IllegalArgumentException exception) {
            throw failure(STATUS_INVALID);
        }
    }

    private ChatMemoryItemDO requireOwnedLocked(String userId, long memoryId) {
        ChatMemoryItemDO item = itemMapper.lockOwnedItem(userId, memoryId);
        if (item == null) {
            throw failure(NOT_FOUND);
        }
        return item;
    }

    private void requireQuarantinedVersion(ChatMemoryItemDO item, long expectedVersion) {
        if (!ChatMemoryStatus.QUARANTINED.name().equals(item.getStatus())
                || item.getVersion() != expectedVersion) {
            throw failure(STATE_CONFLICT);
        }
    }

    private boolean isCurrentOlderConflict(
            String userId, ChatMemoryItemDO candidate, ChatMemoryItemDO target) {
        return target != null
                && Objects.equals(userId, target.getUserId())
                && ChatMemoryStatus.ACTIVE.name().equals(target.getStatus())
                && Objects.equals(candidate.getCategory(), target.getCategory())
                && Objects.equals(candidate.getSubjectKey(), target.getSubjectKey())
                && hasSourceOrder(candidate)
                && hasSourceOrder(target)
                && SOURCE_ORDER.compare(target, candidate) < 0;
    }

    private boolean hasSourceOrder(ChatMemoryItemDO item) {
        return item.getOriginMessageCreatedAt() != null
                && item.getOriginMessagePk() != null
                && item.getOriginQuoteStart() != null;
    }

    private ChatMemoryEvolutionDO relation(
            String userId,
            long fromMemoryId,
            long toMemoryId,
            LocalDateTime createdAt) {
        ChatMemoryEvolutionDO relation = new ChatMemoryEvolutionDO();
        relation.setUserId(userId);
        relation.setFromMemoryId(fromMemoryId);
        relation.setToMemoryId(toMemoryId);
        relation.setRelationType(ChatMemoryRelationType.SUPERSEDES.name());
        relation.setCreatedAt(createdAt);
        return relation;
    }

    private void requireWrite(int affectedRows) {
        if (affectedRows != 1) {
            throw failure(STATE_CONFLICT);
        }
    }

    private Map<Long, List<String>> groupSourceMessageIds(List<ChatMemorySourceDO> sources) {
        Map<Long, List<String>> result = new HashMap<>();
        for (ChatMemorySourceDO source : sources) {
            result.computeIfAbsent(source.getMemoryId(), ignored -> new ArrayList<>())
                    .add(source.getSourceMessageId());
        }
        result.replaceAll((ignored, messageIds) -> List.copyOf(messageIds));
        return result;
    }

    private ChatMemoryView toView(ChatMemoryItemDO item, List<String> sourceMessageIds) {
        return new ChatMemoryView(
                item.getId(),
                item.getCategory(),
                item.getSubjectKey(),
                item.getSubjectCardinality(),
                item.getContent(),
                item.getStatus(),
                item.getQuarantineReason(),
                item.getVersion(),
                toInstant(item.getValidFrom()),
                toInstant(item.getValidUntil()),
                toInstant(item.getCreatedAt()),
                toInstant(item.getUpdatedAt()),
                sourceMessageIds);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private ChatMemoryManagementException failure(String code) {
        return new ChatMemoryManagementException(code);
    }
}

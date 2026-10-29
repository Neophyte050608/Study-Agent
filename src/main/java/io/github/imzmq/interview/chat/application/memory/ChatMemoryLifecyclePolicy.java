package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCardinality;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryQuarantineReason;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryStatus;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ChatMemoryLifecyclePolicy {

    public Decision decide(
            ChatMemoryLifecycleRequest request,
            List<CurrentItem> currentItems) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.contentHash(), "contentHash");
        Objects.requireNonNull(request.cardinality(), "cardinality");
        Objects.requireNonNull(request.originMessageCreatedAt(), "originMessageCreatedAt");

        List<CurrentItem> active = currentItems == null
                ? List.of()
                : currentItems.stream()
                        .filter(Objects::nonNull)
                        .filter(item -> item.status() == ChatMemoryStatus.ACTIVE)
                        .sorted(Comparator.comparingLong(CurrentItem::id))
                        .toList();
        List<Long> relatedIds = active.stream().map(CurrentItem::id).toList();

        if (request.ambiguousBatch() && request.cardinality() == ChatMemoryCardinality.SINGLE) {
            return quarantine(ChatMemoryQuarantineReason.AMBIGUOUS_BATCH_ORDER, relatedIds);
        }
        if (active.stream().anyMatch(item -> item.cardinality() != request.cardinality())) {
            return quarantine(ChatMemoryQuarantineReason.SUBJECT_CARDINALITY_CONFLICT, relatedIds);
        }
        if (request.cardinality() == ChatMemoryCardinality.SINGLE && active.size() > 1) {
            return quarantine(ChatMemoryQuarantineReason.MULTIPLE_ACTIVE_SINGLETONS, relatedIds);
        }

        CurrentItem sameContent = active.stream()
                .filter(item -> request.contentHash().equals(item.contentHash()))
                .findFirst()
                .orElse(null);
        if (sameContent != null) {
            return new Decision(Action.ATTACH_SOURCE, null, List.of(sameContent.id()));
        }
        if (active.isEmpty() || request.cardinality() == ChatMemoryCardinality.MULTI) {
            return new Decision(Action.ACTIVATE, null, List.of());
        }

        CurrentItem current = active.getFirst();
        if (compareSourceOrder(request, current) > 0) {
            return new Decision(Action.SUPERSEDE, null, List.of(current.id()));
        }
        return quarantine(ChatMemoryQuarantineReason.OLDER_THAN_CURRENT, List.of(current.id()));
    }

    private int compareSourceOrder(
            ChatMemoryLifecycleRequest request,
            CurrentItem current) {
        int timestamp = request.originMessageCreatedAt()
                .compareTo(current.originMessageCreatedAt());
        if (timestamp != 0) {
            return timestamp;
        }
        int messagePk = Long.compare(request.originMessagePk(), current.originMessagePk());
        if (messagePk != 0) {
            return messagePk;
        }
        return Integer.compare(request.originQuoteStart(), current.originQuoteStart());
    }

    private Decision quarantine(
            ChatMemoryQuarantineReason reason,
            List<Long> relatedMemoryIds) {
        return new Decision(Action.QUARANTINE, reason, relatedMemoryIds);
    }

    public enum Action {
        ATTACH_SOURCE,
        ACTIVATE,
        SUPERSEDE,
        QUARANTINE
    }

    public record CurrentItem(
            long id,
            String contentHash,
            ChatMemoryStatus status,
            ChatMemoryCardinality cardinality,
            LocalDateTime originMessageCreatedAt,
            long originMessagePk,
            int originQuoteStart,
            long version
    ) {
    }

    public record Decision(
            Action action,
            ChatMemoryQuarantineReason quarantineReason,
            List<Long> relatedMemoryIds
    ) {
        public Decision {
            Objects.requireNonNull(action, "action");
            relatedMemoryIds = relatedMemoryIds == null
                    ? List.of()
                    : relatedMemoryIds.stream().sorted().distinct().toList();
            if ((action == Action.QUARANTINE) != (quarantineReason != null)) {
                throw new IllegalArgumentException(
                        "quarantine reason must exist only for quarantine decisions");
            }
        }
    }
}

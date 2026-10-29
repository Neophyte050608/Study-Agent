package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCategory;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryProjectionPort;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryVectorCandidate;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ChatMemoryRecallService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryRecallService.class);

    private final Optional<ChatMemoryProjectionPort> projectionPort;
    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemoryProperties properties;
    private final ExecutorService recallExecutor;
    private final ChatMemoryObservationPublisher observationPublisher;
    private final Clock clock;

    public ChatMemoryRecallService(
            Optional<ChatMemoryProjectionPort> projectionPort,
            ChatMemoryItemMapper itemMapper,
            ChatMemoryProperties properties,
            @Qualifier("chatMemoryRecallExecutor") ExecutorService recallExecutor,
            ChatMemoryObservationPublisher observationPublisher,
            Clock clock) {
        this.projectionPort = projectionPort;
        this.itemMapper = itemMapper;
        this.properties = properties;
        this.recallExecutor = recallExecutor;
        this.observationPublisher = observationPublisher;
        this.clock = clock;
    }

    public List<RecalledMemory> recall(String userId, String query) {
        if (!properties.isEnabled() || userId == null || userId.isBlank()
                || query == null || query.isBlank()) {
            return List.of();
        }
        long startedAt = System.nanoTime();
        if (Thread.currentThread().isInterrupted()) {
            publishRecall("EMPTY", 0, false, startedAt, null);
            return List.of();
        }

        VectorAttempt vectorAttempt = searchVector(userId, query);
        if (vectorAttempt.interrupted()) {
            publishRecall("EMPTY", 0, false, startedAt, null);
            return List.of();
        }
        if (vectorAttempt.candidates() != null) {
            try {
                List<RecalledMemory> validated = validateCandidates(userId, vectorAttempt.candidates());
                if (!validated.isEmpty()) {
                    publishRecall("HIT", validated.size(), false, startedAt, null);
                    return validated;
                }
            } catch (RuntimeException exception) {
                log.warn("Chat memory MySQL revalidation failed; recall returns empty");
                publishRecall("FAILED", 0, false, startedAt, "MEMORY_RECALL_TRUTH_FAILED");
                return List.of();
            }
        }
        List<RecalledMemory> fallback = lexicalFallback(userId, query);
        publishRecall(fallback.isEmpty() ? "EMPTY" : "FALLBACK",
                fallback.size(), true, startedAt, null);
        return fallback;
    }

    private void publishRecall(
            String status, int resultCount, boolean fallback, long startedAt, String errorCode) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("stage", "recall");
        attributes.put("resultCount", resultCount);
        attributes.put("fallback", fallback);
        attributes.put("latencyMs",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        if (errorCode != null) {
            attributes.put("errorCode", errorCode);
        }
        observationPublisher.recall(status, attributes);
    }

    private VectorAttempt searchVector(String userId, String query) {
        if (projectionPort.isEmpty()) {
            return VectorAttempt.failed();
        }
        Duration timeout = properties.getRecallTimeout();
        Future<List<ChatMemoryVectorCandidate>> future;
        try {
            future = recallExecutor.submit(() -> projectionPort.orElseThrow().search(
                    userId, query, properties.getRecallCandidates(), timeout));
        } catch (RuntimeException exception) {
            log.warn("Chat memory vector recall was not scheduled; using lexical fallback");
            return VectorAttempt.failed();
        }
        try {
            return VectorAttempt.succeeded(future.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return VectorAttempt.interruptedAttempt();
        } catch (TimeoutException exception) {
            // Cancellation is best effort. The SDK call also receives the same RPC deadline.
            future.cancel(true);
            log.warn("Chat memory vector recall timed out; using lexical fallback");
            return VectorAttempt.failed();
        } catch (ExecutionException | RuntimeException exception) {
            log.warn("Chat memory vector recall failed; using lexical fallback");
            return VectorAttempt.failed();
        }
    }

    private List<RecalledMemory> validateCandidates(
            String userId, List<ChatMemoryVectorCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Long> memoryIds = candidates.stream()
                .map(ChatMemoryVectorCandidate::memoryId)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (memoryIds.isEmpty()) {
            return List.of();
        }

        LocalDateTime now = lifecycleNow();
        Map<Long, ChatMemoryItemDO> itemsById = new HashMap<>();
        for (ChatMemoryItemDO item : itemMapper.selectRecallItems(userId, memoryIds, now)) {
            if (item != null && item.getId() != null) {
                itemsById.put(item.getId(), item);
            }
        }

        List<RecalledMemory> recalled = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ChatMemoryVectorCandidate candidate : candidates) {
            ChatMemoryItemDO item = itemsById.get(candidate.memoryId());
            ChatMemoryCategory category = exactCategory(candidate.category(), item);
            if (category == null
                    || !seen.add(candidate.memoryId())
                    || !matchesProjection(userId, candidate, item, now)) {
                continue;
            }
            recalled.add(new RecalledMemory(item.getId(), category, item.getContent()));
            if (recalled.size() >= properties.getRecallLimit()) {
                break;
            }
        }
        return List.copyOf(recalled);
    }

    private boolean matchesProjection(
            String userId, ChatMemoryVectorCandidate candidate, ChatMemoryItemDO item,
            LocalDateTime now) {
        return item != null
                && userId.equals(item.getUserId())
                && isCurrentlyValid(item, now)
                && item.getVersion() != null
                && item.getVersion() == candidate.memoryVersion()
                && item.getContentHash() != null
                && item.getContentHash().equals(candidate.contentHash())
                && item.getCategory() != null
                && item.getCategory().equals(candidate.category())
                && item.getContent() != null
                && !item.getContent().isBlank()
                && (candidate.memoryId() + ":" + candidate.memoryVersion())
                        .equals(candidate.projectionId());
    }

    private ChatMemoryCategory exactCategory(String candidateCategory, ChatMemoryItemDO item) {
        if (item == null || candidateCategory == null || !candidateCategory.equals(item.getCategory())) {
            return null;
        }
        try {
            return ChatMemoryCategory.valueOf(candidateCategory);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<RecalledMemory> lexicalFallback(String userId, String query) {
        if (Thread.currentThread().isInterrupted()) {
            return List.of();
        }
        try {
            Set<String> queryTokens = tokenize(query);
            List<RankedMemory> ranked = new ArrayList<>();
            int recency = 0;
            LocalDateTime now = lifecycleNow();
            for (ChatMemoryItemDO item : itemMapper.selectRecentEffectiveItems(
                    userId, properties.getFallbackScanLimit(), now)) {
                RankedMemory candidate = rankFallback(userId, item, queryTokens, recency++, now);
                if (candidate != null) {
                    ranked.add(candidate);
                }
            }
            ranked.sort(Comparator
                    .comparingInt(RankedMemory::overlap).reversed()
                    .thenComparingInt(RankedMemory::recency));
            return ranked.stream()
                    .limit(properties.getRecallLimit())
                    .map(RankedMemory::memory)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("Chat memory lexical fallback failed; recall returns empty");
            return List.of();
        }
    }

    private RankedMemory rankFallback(
            String userId, ChatMemoryItemDO item, Set<String> queryTokens, int recency,
            LocalDateTime now) {
        if (item == null
                || !userId.equals(item.getUserId())
                || !isCurrentlyValid(item, now)
                || item.getContent() == null
                || item.getContent().isBlank()) {
            return null;
        }
        ChatMemoryCategory category;
        try {
            category = ChatMemoryCategory.valueOf(item.getCategory());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
        Set<String> contentTokens = tokenize(item.getContent());
        int overlap = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                overlap++;
            }
        }
        boolean stableBackground = category == ChatMemoryCategory.PREFERENCE
                || category == ChatMemoryCategory.GOAL;
        if (overlap == 0 && !stableBackground) {
            return null;
        }
        return new RankedMemory(
                new RecalledMemory(item.getId(), category, item.getContent()), overlap, recency);
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        StringBuilder han = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint) && !isHan(codePoint)) {
                flushHan(han, tokens);
                word.appendCodePoint(codePoint);
            } else if (isHan(codePoint)) {
                flushWord(word, tokens);
                han.appendCodePoint(codePoint);
            } else {
                flushWord(word, tokens);
                flushHan(han, tokens);
            }
        });
        flushWord(word, tokens);
        flushHan(han, tokens);
        return Set.copyOf(tokens);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private void flushWord(StringBuilder word, Set<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private void flushHan(StringBuilder han, Set<String> tokens) {
        if (han.isEmpty()) {
            return;
        }
        int[] codePoints = han.codePoints().toArray();
        for (int codePoint : codePoints) {
            tokens.add(new String(Character.toChars(codePoint)));
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            tokens.add(new String(codePoints, index, 2));
        }
        han.setLength(0);
    }

    private LocalDateTime lifecycleNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private boolean isCurrentlyValid(ChatMemoryItemDO item, LocalDateTime now) {
        return "ACTIVE".equals(item.getStatus())
                && item.getValidFrom() != null
                && !item.getValidFrom().isAfter(now)
                && (item.getValidUntil() == null || item.getValidUntil().isAfter(now));
    }

    public record RecalledMemory(long id, ChatMemoryCategory category, String content) {
    }

    private record RankedMemory(RecalledMemory memory, int overlap, int recency) {
    }

    private record VectorAttempt(List<ChatMemoryVectorCandidate> candidates, boolean interrupted) {
        private static VectorAttempt succeeded(List<ChatMemoryVectorCandidate> candidates) {
            return new VectorAttempt(candidates == null ? List.of() : List.copyOf(candidates), false);
        }

        private static VectorAttempt failed() {
            return new VectorAttempt(null, false);
        }

        private static VectorAttempt interruptedAttempt() {
            return new VectorAttempt(null, true);
        }
    }
}

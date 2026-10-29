package io.github.imzmq.interview.chat.application.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCategory;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySourceQuote;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySubjectKey;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySubjectPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ChatMemoryCandidateValidator {

    private static final int MAX_CANDIDATES = 8;
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "category", "subjectKey", "content", "sourceMessageId", "quote");
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)(ignore|disregard|forget).{0,30}(previous|prior|system|instruction|prompt)"
                    + "|忽略.{0,20}(之前|以上|系统|指令|提示词)"
                    + "|system\\s*prompt|developer\\s*message");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(api[_ -]?key|access[_ -]?token|secret|password|passwd|authorization)\\s*[:=]\\s*\\S{4,}"
                    + "|sk-[a-z0-9_-]{8,}|bearer\\s+[a-z0-9._-]{8,}");

    public List<ChatMemoryCandidate> validate(
            JsonNode root,
            String expectedUserId,
            String expectedSessionId,
            Set<String> allowedSourceMessageIds,
            List<ChatMemorySourceMessage> sources) {
        if (root == null || !root.isObject() || root.size() != 1
                || !root.has("memories") || !root.get("memories").isArray()) {
            throw invalid("MEMORY_SCHEMA_INVALID");
        }
        JsonNode memories = root.get("memories");
        if (memories.size() > MAX_CANDIDATES) {
            throw invalid("MEMORY_TOO_MANY_CANDIDATES");
        }

        Map<String, ChatMemorySourceMessage> sourceById = new HashMap<>();
        for (ChatMemorySourceMessage source : sources) {
            sourceById.put(source.messageId(), source);
        }

        List<ChatMemoryCandidate> candidates = new ArrayList<>(memories.size());
        for (JsonNode node : memories) {
            candidates.add(validateOne(node, expectedUserId, expectedSessionId,
                    allowedSourceMessageIds, sourceById));
        }
        return List.copyOf(candidates);
    }

    private ChatMemoryCandidate validateOne(
            JsonNode node,
            String expectedUserId,
            String expectedSessionId,
            Set<String> allowedSourceMessageIds,
            Map<String, ChatMemorySourceMessage> sourceById) {
        if (!node.isObject() || fieldNames(node).size() != REQUIRED_FIELDS.size()
                || !fieldNames(node).equals(REQUIRED_FIELDS)) {
            throw invalid("MEMORY_ITEM_SCHEMA_INVALID");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!node.get(field).isTextual() || node.get(field).textValue().isBlank()) {
                throw invalid("MEMORY_ITEM_SCHEMA_INVALID");
            }
        }

        String sourceMessageId = node.get("sourceMessageId").textValue();
        if (!allowedSourceMessageIds.contains(sourceMessageId)) {
            throw invalid("MEMORY_SOURCE_NOT_ALLOWED");
        }
        ChatMemorySourceMessage source = sourceById.get(sourceMessageId);
        if (source == null || source.deleted() || !"user".equals(source.role())
                || !expectedUserId.equals(source.userId())
                || !expectedSessionId.equals(source.sessionId())) {
            throw invalid("MEMORY_SOURCE_INVALID");
        }

        String content = node.get("content").textValue();
        String quote = node.get("quote").textValue();
        if (isUnsafe(content) || isUnsafe(quote)) {
            throw invalid("MEMORY_UNSAFE_CANDIDATE");
        }
        int charStart = source.content() == null ? -1 : source.content().indexOf(quote);
        if (charStart < 0) {
            throw invalid("MEMORY_QUOTE_MISMATCH");
        }
        int start = source.content().codePointCount(0, charStart);
        int end = start + quote.codePointCount(0, quote.length());

        try {
            ChatMemoryCategory category = ChatMemoryCategory.valueOf(
                    node.get("category").textValue());
            ChatMemorySubjectKey subjectKey = ChatMemorySubjectKey.parse(
                    category, node.get("subjectKey").textValue());
            ChatMemorySubjectKey canonicalSubjectKey = ChatMemorySubjectPolicy.canonicalize(
                    category, subjectKey);
            return new ChatMemoryCandidate(category, canonicalSubjectKey, content,
                    new ChatMemorySourceQuote(sourceMessageId, start, end, quote));
        } catch (IllegalArgumentException exception) {
            throw new ChatMemoryExtractionException("MEMORY_CANDIDATE_INVALID", exception);
        }
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private boolean isUnsafe(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return PROMPT_INJECTION.matcher(normalized).find()
                || CREDENTIAL.matcher(normalized).find();
    }

    private ChatMemoryExtractionException invalid(String code) {
        return new ChatMemoryExtractionException(code);
    }
}

package io.github.imzmq.interview.knowledge.application.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentDO;
import io.github.imzmq.interview.config.knowledge.ParentChildRetrievalProperties;
import io.github.imzmq.interview.knowledge.application.indexing.ParentChildIndexService;
import io.github.imzmq.interview.media.application.contract.result.ImageResult;

/**
 * 装配注入模型的上下文、编号证据与图片，父文读取复用 Knowledge 自有索引服务。
 * 不执行召回或外部检索，也不改变 Web fallback 的决策规则。
 */
@Service
public class GroundingContextAssembler {
    private static final double FINAL_IMAGE_MIN_SCORE = 0.55d;
    private static final double SECOND_IMAGE_MIN_SCORE = 0.68d;
    private static final double ADDITIONAL_IMAGE_SCORE_GAP = 0.18d;
    private static final Set<String> VISUAL_INTENT_KEYWORDS = Set.of(
            "图", "截图", "架构", "流程图", "拓扑", "示意图", "类图", "时序图",
            "部署图", "结构图", "原理图", "对比图", "配置图", "界面", "效果图",
            "看看", "展示", "图解", "diagram", "topology", "layout", "screenshot"
    );
    private final ParentChildRetrievalProperties parentChildRetrievalProperties;
    private final ParentChildIndexService parentChildIndexService;

    public GroundingContextAssembler(ParentChildRetrievalProperties parentChildRetrievalProperties, ParentChildIndexService parentChildIndexService) {
        this.parentChildRetrievalProperties = parentChildRetrievalProperties;
        this.parentChildIndexService = parentChildIndexService;
    }

    public String buildContext(List<Document> documents) {
        return documents.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
    }

    public String buildWebContext(List<String> webContext) {
        return webContext.stream().collect(Collectors.joining("\n\n"));
    }

    /** 保留子块命中片段，补充命中附近的父文窗口；只回填前 topK/配置上限内的候选。 */
    public List<Document> hydrateParentDocuments(List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        if (!parentChildRetrievalProperties.isEnabled()) {
            return docs;
        }
        int hydrateLimit = Math.max(1, Math.min(parentChildRetrievalProperties.getHydrateParentTopN(), Math.max(1, topK)));
        List<Document> candidates = docs.stream().limit(hydrateLimit).collect(Collectors.toList());
        Set<String> parentIds = candidates.stream()
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("parent_id", "")))
                .filter(item -> item != null && !item.isBlank() && !"null".equalsIgnoreCase(item))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parentIds.isEmpty()) {
            return docs;
        }
        Map<String, RagParentDO> parentMap = parentChildIndexService.queryParentsByIds(parentIds);
        if (parentMap.isEmpty()) {
            return docs;
        }
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            if (i >= hydrateLimit) {
                result.add(doc);
                continue;
            }
            String parentId = String.valueOf(doc.getMetadata().getOrDefault("parent_id", ""));
            RagParentDO parent = parentMap.get(parentId);
            if (parent == null || parent.getParentText() == null || parent.getParentText().isBlank()) {
                result.add(doc);
                continue;
            }
            ParentChildHydrationPayload payload = buildParentChildHydrationPayload(doc, parent);
            Document hydrated = new Document(payload.hydratedText());
            hydrated.getMetadata().putAll(doc.getMetadata());
            hydrated.getMetadata().put("parent_id", parent.getParentId());
            hydrated.getMetadata().put("parent_source", parent.getFilePath());
            hydrated.getMetadata().put("file_path", parent.getFilePath());
            hydrated.getMetadata().put("section_path", parent.getSectionPath());
            hydrated.getMetadata().put("knowledge_tags", parent.getKnowledgeTags());
            hydrated.getMetadata().put("source_type", parent.getSourceType());
            hydrated.getMetadata().put("hydration_mode", "parent_child_concat");
            hydrated.getMetadata().put("parent_context_excerpt", payload.parentContextExcerpt());
            hydrated.getMetadata().put("child_match_excerpt", payload.childMatchExcerpt());
            hydrated.getMetadata().put("evidence_snippet", payload.evidenceSnippet());
            result.add(hydrated);
        }
        return result;
    }

    private ParentChildHydrationPayload buildParentChildHydrationPayload(Document doc, RagParentDO parent) {
        String childMatchExcerpt = extractChildMatchExcerpt(doc == null ? "" : doc.getText());
        String parentContextExcerpt = extractParentContextExcerpt(parent == null ? "" : parent.getParentText(), childMatchExcerpt);
        String hydratedText = composeHydratedText(parent, parentContextExcerpt, childMatchExcerpt);
        String evidenceSnippet = buildHydratedEvidenceSnippet(parentContextExcerpt, childMatchExcerpt);
        return new ParentChildHydrationPayload(hydratedText, parentContextExcerpt, childMatchExcerpt, evidenceSnippet);
    }

    private String extractChildMatchExcerpt(String childText) {
        if (childText == null || childText.isBlank()) {
            return "";
        }
        String normalized = childText.replace("\r\n", "\n").trim();
        String[] parts = normalized.split("\\n", 2);
        String candidate = parts.length >= 2 ? parts[1] : normalized;
        candidate = candidate.replaceFirst("^(\\[[^\\]]+\\]\\s*)+", "");
        candidate = normalizeSnippet(candidate);
        return truncate(candidate, Math.max(60, parentChildRetrievalProperties.getHydrateChildMatchChars()));
    }

    private String extractParentContextExcerpt(String parentText, String childMatchExcerpt) {
        String normalizedParent = normalizeSnippet(parentText);
        if (normalizedParent.isBlank()) {
            return "";
        }
        int maxChars = Math.max(160, parentChildRetrievalProperties.getHydrateParentContextChars());
        String anchor = resolveParentAnchor(normalizedParent, childMatchExcerpt);
        if (anchor.isBlank()) {
            return truncate(normalizedParent, maxChars);
        }
        int anchorStart = normalizedParent.indexOf(anchor);
        if (anchorStart < 0) {
            return truncate(normalizedParent, maxChars);
        }
        return excerptAroundRange(normalizedParent, anchorStart, anchorStart + anchor.length(), maxChars);
    }

    private String resolveParentAnchor(String parentText, String childMatchExcerpt) {
        String normalizedChild = normalizeSnippet(childMatchExcerpt);
        if (normalizedChild.isBlank()) {
            return "";
        }
        int maxLength = Math.min(80, normalizedChild.length());
        for (int length = maxLength; length >= 18; length -= 10) {
            String candidate = normalizedChild.substring(0, length);
            if (parentText.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String excerptAroundRange(String text, int start, int end, int maxChars) {
        if (text == null || text.isBlank() || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int safeStart = Math.max(0, start - maxChars / 3);
        int safeEnd = Math.min(text.length(), safeStart + maxChars);
        if (safeEnd < end) {
            safeEnd = Math.min(text.length(), end + maxChars / 3);
            safeStart = Math.max(0, safeEnd - maxChars);
        }
        String excerpt = text.substring(safeStart, safeEnd).trim();
        if (safeStart > 0) {
            excerpt = "..." + excerpt;
        }
        if (safeEnd < text.length()) {
            excerpt = excerpt + "...";
        }
        return excerpt;
    }

    private String composeHydratedText(RagParentDO parent, String parentContextExcerpt, String childMatchExcerpt) {
        StringBuilder builder = new StringBuilder();
        if (parent != null && parent.getSectionPath() != null && !parent.getSectionPath().isBlank()) {
            builder.append("\u3010\u7AE0\u8282\u8DEF\u5F84\u3011").append(parent.getSectionPath().trim()).append("\n");
        }
        if (parentContextExcerpt != null && !parentContextExcerpt.isBlank()) {
            builder.append("\u3010\u7236\u6587\u4E0A\u4E0B\u6587\u3011").append(parentContextExcerpt.trim());
        }
        if (childMatchExcerpt != null && !childMatchExcerpt.isBlank()) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("\u3010\u547D\u4E2D\u7247\u6BB5\u3011").append(childMatchExcerpt.trim());
        }
        return builder.toString().trim();
    }

    private String buildHydratedEvidenceSnippet(String parentContextExcerpt, String childMatchExcerpt) {
        String contextPart = truncate(normalizeSnippet(parentContextExcerpt), 48);
        String matchPart = truncate(normalizeSnippet(childMatchExcerpt), 36);
        if (!matchPart.isBlank() && !contextPart.isBlank()) {
            return "\u547D\u4E2D=" + matchPart + " | \u4E0A\u6587=" + contextPart;
        }
        if (!matchPart.isBlank()) {
            return "\u547D\u4E2D=" + matchPart;
        }
        return contextPart;
    }

    private String normalizeSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 编号及来源格式供 citations/conflicts 校验复用，优先使用回填产生的 evidence_snippet。 */
    public String buildRetrievalEvidence(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "[]";
        }
        // 证据目录格式："{index}. [source_type:file_path] tags=... | snippet"
        // 该格式会被 parseEvidenceCatalog 与 validateEvidenceReferences 使用，以约束 citations/conflicts 的可引用范围。
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Map<String, Object> metadata = doc.getMetadata();
            String path = metadata == null ? "" : String.valueOf(metadata.getOrDefault("file_path", "unknown"));
            String sourceType = metadata == null ? "" : String.valueOf(metadata.getOrDefault("source_type", "obsidian"));
            String parentId = metadata == null ? "" : String.valueOf(metadata.getOrDefault("parent_id", ""));
            String childIndex = metadata == null ? "" : String.valueOf(metadata.getOrDefault("child_index", ""));
            String tags = metadata == null ? "" : String.valueOf(metadata.getOrDefault("knowledge_tags", ""));
            String text = metadata != null
                    && metadata.get("evidence_snippet") != null
                    && !String.valueOf(metadata.get("evidence_snippet")).isBlank()
                    ? String.valueOf(metadata.get("evidence_snippet"))
                    : normalizeSnippet(doc.getText());
            text = truncate(text, 90);
            String parentPart = (parentId == null || parentId.isBlank() || "null".equalsIgnoreCase(parentId)) ? "" : " parent=" + parentId;
            String childPart = (childIndex == null || childIndex.isBlank() || "null".equalsIgnoreCase(childIndex)) ? "" : " child=" + childIndex;
            lines.add((i + 1) + ". [" + sourceType + ":" + path + "] tags=" + tags + parentPart + childPart + " | " + text);
        }
        return String.join("\n", lines);
    }

    public String buildWebEvidence(List<String> webContext) {
        if (webContext == null || webContext.isEmpty()) {
            return "[]";
        }
        // Web 证据没有稳定的 file_path/source_type，统一标记为 [web] 并做摘要截断。
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < webContext.size(); i++) {
            String text = webContext.get(i);
            if (text == null) {
                continue;
            }
            String normalized = text.replaceAll("\\s+", " ").trim();
            if (normalized.length() > 90) {
                normalized = normalized.substring(0, 90);
            }
            lines.add((i + 1) + ". [web] " + normalized);
        }
        return lines.isEmpty() ? "[]" : String.join("\n", lines);
    }

    public List<String> summarizeRetrievedDocuments(List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return List.of();
        }
        return retrievedDocs.stream()
                .limit(5)
                .map(document -> {
                    String source = String.valueOf(document.getMetadata().getOrDefault("source", ""));
                    String title = String.valueOf(document.getMetadata().getOrDefault("title", ""));
                    String snippet = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
                    if (snippet.length() > 80) {
                        snippet = snippet.substring(0, 80) + "...";
                    }
                    return Stream.of(source, title, snippet)
                            .filter(item -> item != null && !item.isBlank() && !"null".equalsIgnoreCase(item))
                            .collect(Collectors.joining(" | "));
                })
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    public List<ImageResult> mergeImageResults(List<ImageResult> associatedImages,
                                                             List<ImageResult> semanticImages) {
        Map<String, ImageResult> merged = new LinkedHashMap<>();
        if (semanticImages != null) {
            for (ImageResult image : semanticImages) {
                merged.put(image.imageId(), image);
            }
        }
        if (associatedImages != null) {
            for (ImageResult image : associatedImages) {
                merged.compute(image.imageId(), (key, existing) ->
                        existing == null || image.relevanceScore() > existing.relevanceScore() ? image : existing);
            }
        }
        List<ImageResult> ranked = merged.values().stream()
                .sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()))
                .limit(3)
                .toList();
        if (ranked.isEmpty()) {
            return ranked;
        }
        double topScore = ranked.getFirst().relevanceScore();
        if (topScore < FINAL_IMAGE_MIN_SCORE) {
            return List.of();
        }
        List<ImageResult> filtered = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            ImageResult image = ranked.get(i);
            if (i == 0) {
                filtered.add(image);
                continue;
            }
            if (image.relevanceScore() < SECOND_IMAGE_MIN_SCORE) {
                continue;
            }
            if (topScore - image.relevanceScore() > ADDITIONAL_IMAGE_SCORE_GAP) {
                continue;
            }
            filtered.add(image);
        }
        return filtered;
    }

    public boolean containsVisualIntent(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return VISUAL_INTENT_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private record ParentChildHydrationPayload(
            String hydratedText,
            String parentContextExcerpt,
            String childMatchExcerpt,
            String evidenceSnippet
    ) {
    }
}

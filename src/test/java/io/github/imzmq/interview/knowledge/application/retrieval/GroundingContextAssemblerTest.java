package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.config.knowledge.ParentChildRetrievalProperties;
import io.github.imzmq.interview.knowledge.application.indexing.ParentChildIndexService;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentDO;
import io.github.imzmq.interview.media.application.contract.result.ImageResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GroundingContextAssemblerTest {
    private final ParentChildRetrievalProperties properties = new ParentChildRetrievalProperties();
    private final ParentChildIndexService parents = mock(ParentChildIndexService.class);
    private final GroundingContextAssembler assembler = new GroundingContextAssembler(properties, parents);

    @Test
    void hydratesOnlyTopCandidatesAndKeepsMetadataAndEvidenceFormat() {
        Document child = new Document("heading\n[tag] redis cache", Map.of("parent_id", "p", "child_index", 0, "retrieve_channel", "global_vector"));
        Document untouched = new Document("second", Map.of("parent_id", "p"));
        RagParentDO parent = new RagParentDO();
        parent.setParentId("p");
        parent.setParentText("redis cache broader context");
        parent.setFilePath("cache.md");
        parent.setSectionPath("Redis > cache");
        parent.setKnowledgeTags("cache");
        parent.setSourceType("obsidian");
        when(parents.queryParentsByIds(Set.of("p"))).thenReturn(Map.of("p", parent));

        List<Document> hydrated = assembler.hydrateParentDocuments(List.of(child, untouched), 1);

        assertThat(hydrated.get(1)).isSameAs(untouched);
        assertThat(hydrated.getFirst().getText()).isEqualTo("【章节路径】Redis > cache\n【父文上下文】redis cache broader context\n\n【命中片段】redis cache");
        assertThat(hydrated.getFirst().getMetadata()).containsEntry("hydration_mode", "parent_child_concat")
                .containsEntry("retrieve_channel", "global_vector").containsEntry("file_path", "cache.md");
        assertThat(assembler.buildRetrievalEvidence(hydrated)).startsWith(
                "1. [obsidian:cache.md] tags=cache parent=p child=0 | 命中=redis cache | 上文=redis cache broader context");
        assertThat(child.getMetadata()).doesNotContainKey("hydration_mode");
    }

    @Test
    void keepsOriginalDocumentWhenHydrationDisabledMissingOrBlankParent() {
        Document child = new Document("child", Map.of("parent_id", "p"));
        properties.setEnabled(false);
        assertThat(assembler.hydrateParentDocuments(List.of(child), 1)).containsExactly(child);
        verifyNoInteractions(parents);
        properties.setEnabled(true);
        when(parents.queryParentsByIds(any())).thenReturn(Map.of());
        assertThat(assembler.hydrateParentDocuments(List.of(child), 1)).containsExactly(child);
        assertThat(assembler.hydrateParentDocuments(null, 1)).isEmpty();
    }

    @Test
    void locatesChildInLongParentAndFallsBackToHeadWhenAnchorIsMissing() {
        properties.setHydrateParentContextChars(160);
        properties.setHydrateChildMatchChars(60);
        String anchor = "redis cache invalidation strategy";
        Document child = new Document(anchor, Map.of("parent_id", "p"));
        RagParentDO parent = new RagParentDO();
        parent.setParentId("p");
        parent.setParentText("prefix ".repeat(60) + anchor + " suffix".repeat(60));
        when(parents.queryParentsByIds(any())).thenReturn(Map.of("p", parent));
        Document hydrated = assembler.hydrateParentDocuments(List.of(child), 1).getFirst();
        assertThat((String) hydrated.getMetadata().get("parent_context_excerpt"))
                .startsWith("...").endsWith("...").contains(anchor).hasSizeLessThanOrEqualTo(166);
        parent.setParentText("prefix ".repeat(60));
        hydrated = assembler.hydrateParentDocuments(List.of(child), 1).getFirst();
        assertThat(hydrated.getMetadata().get("parent_context_excerpt")).isEqualTo(parent.getParentText().trim().substring(0, 160) + "...");
        parent.setParentText("  ");
        assertThat(assembler.hydrateParentDocuments(List.of(child), 1).getFirst()).isSameAs(child);
    }

    @Test
    void preservesEvidenceNumberingWhitespaceAndTruncation() {
        assertThat(assembler.buildWebEvidence(Arrays.asList("hello\nworld", null, "x".repeat(100))))
                .isEqualTo("1. [web] hello world\n3. [web] " + "x".repeat(90));
        assertThat(assembler.buildRetrievalEvidence(List.of(new Document("x".repeat(100)))))
                .isEqualTo("1. [obsidian:unknown] tags= | " + "x".repeat(90) + "...");
        assertThat(assembler.buildRetrievalEvidence(null)).isEqualTo("[]");
        assertThat(assembler.buildWebEvidence(List.of())).isEqualTo("[]");
        assertThat(assembler.buildContext(List.of(new Document("first"), new Document("second"))))
                .isEqualTo("first\n\nsecond");
        assertThat(assembler.buildWebContext(List.of("web1", "web2"))).isEqualTo("web1\n\nweb2");
    }

    @Test
    void deduplicatesImagesUsingBestScoreAndPreservesExistingThresholds() {
        ImageResult top = image("top", 0.90);
        ImageResult improved = image("top", 0.92);
        ImageResult second = image("second", 0.75);
        assertThat(assembler.mergeImageResults(List.of(improved), List.of(top, second, image("far", 0.70))))
                .containsExactly(improved, second);
        assertThat(assembler.mergeImageResults(null, List.of(image("low", 0.54)))).isEmpty();
        assertThat(assembler.mergeImageResults(List.of(), List.of(top, image("low", 0.67)))).containsExactly(top);
        assertThat(assembler.containsVisualIntent("Show DIAGRAM")).isTrue();
        assertThat(assembler.containsVisualIntent("redis cache")).isFalse();
        assertThat(assembler.containsVisualIntent(null)).isFalse();
        assertThat(assembler.summarizeRetrievedDocuments(List.of(new Document("first\nline", Map.of("source", "note", "title", "Redis")))))
                .containsExactly("note | Redis | first line");
    }

    private ImageResult image(String id, double score) {
        return new ImageResult(id, id, "", "", "", "", score, "semantic");
    }
}

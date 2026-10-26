package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EvidenceFusionServiceTest {
    private final LexicalIndexService lexical = mock(LexicalIndexService.class);
    private final EvidenceFusionService fusion = new EvidenceFusionService(lexical, new RetrievalTokenizerService());

    @Test
    void fusesByPathAndTextHeadRetainingFirstDocumentAndSourceBoosts() {
        Document intent = doc("redis", "a", "obsidian", "intent_directed");
        Document duplicate = doc("redis", "a", "obsidian", "global_vector");
        Document experience = doc("redis", "b", "interview_experience", "global_vector");
        assertThat(fusion.reciprocalRankFuse(List.of(intent), List.of(duplicate, experience)))
                .containsExactly(intent, experience);
        assertThat(fusion.reciprocalRankFuse(List.of(), List.of(duplicate, experience)))
                .containsExactly(experience, duplicate);
    }

    @Test
    void preservesGlobalChannelLexicalScoreAndStableTies() {
        Document first = doc("a", "a", "obsidian", "global_vector");
        Document boosted = doc("b", "b", "obsidian", "global_vector");
        boosted.getMetadata().put("lexical_score", 4.0);
        assertThat(fusion.reciprocalRankFuse(List.of(), List.of(first, boosted))).containsExactly(boosted, first);
        Document samePathOtherText = doc("other", "a", "obsidian", "intent_directed");
        assertThat(fusion.reciprocalRankFuse(List.of(first), List.of(samePathOtherText)))
                .hasSize(2).containsExactly(samePathOtherText, first);
        assertThat(fusion.reciprocalRankFuse(List.of(), List.of())).isEmpty();
    }

    @Test
    void preservesInsertionOrderWhenRrfScoresTie() {
        Document first = new Document("first", Map.of("file_path", "first"));
        Document second = new Document("second", Map.of("file_path", "second"));
        assertThat(fusion.reciprocalRankFuse(List.of(first), List.of(second))).containsExactly(first, second);
    }

    @Test
    void reranksUsingCoreAndExpansionWeightsAndWritesScoresBeforeTopK() {
        when(lexical.tokenize("core")).thenReturn(List.of("core"));
        when(lexical.tokenize("expand")).thenReturn(List.of("expand"));
        when(lexical.tokenize("core doc")).thenReturn(List.of("core"));
        when(lexical.tokenize("expand doc")).thenReturn(List.of("expand"));
        Document core = new Document("core doc");
        Document expand = new Document("expand doc");
        assertThat(fusion.rerankByQueryOverlap(new RewrittenQuery("core", "expand", "core expand"), List.of(expand, core), 1))
                .containsExactly(core);
        assertThat((double) core.getMetadata().get("retrieval_score")).isCloseTo(0.50, org.assertj.core.data.Offset.offset(1e-9));
        assertThat((double) expand.getMetadata().get("retrieval_score")).isCloseTo(0.44, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void keepsOrderForEmptyTokensAndQualityScoreUsesExistingTokenizer() {
        when(lexical.tokenize("")).thenReturn(List.of());
        Document doc = new Document("redis cache", Map.of("source_type", "interview_experience"));
        assertThat(fusion.rerankByQueryOverlap(RewrittenQuery.fallback(""), List.of(doc), 1)).containsExactly(doc);
        assertThat(doc.getMetadata()).doesNotContainKey("retrieval_score");
        assertThat(fusion.bestRetrievalScore("redis", List.of(doc))).isCloseTo(1.18, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(fusion.bestRetrievalScore("", List.of(doc))).isZero();
        assertThat(fusion.bestRetrievalScore("redis", null)).isZero();
    }

    private Document doc(String text, String path, String source, String channel) {
        return new Document(text, Map.of("file_path", path, "source_type", source, "retrieve_channel", channel));
    }
}

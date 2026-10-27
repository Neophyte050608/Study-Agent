package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldSetDatasetValidatorTest {

    private final GoldSetDatasetValidator validator = new GoldSetDatasetValidator();

    @Test
    void acceptsConsistentDataset() {
        List<GoldSetCase> cases = List.of(goldCase("q1", "Redis persistence", List.of("c1")));
        List<GoldSetChunk> chunks = List.of(chunk("c1"));

        assertThatCode(() -> validator.validate(cases, chunks)).doesNotThrowAnyException();
    }

    @Test
    void reportsAllIdentityQuestionAndReferenceViolationsTogether() {
        List<GoldSetCase> cases = List.of(
                goldCase("q1", "Redis persistence", List.of("c1")),
                goldCase("q1", "   ", List.of("missing-chunk"))
        );
        List<GoldSetChunk> chunks = List.of(chunk("c1"), chunk("c1"));

        assertThatThrownBy(() -> validator.validate(cases, chunks))
                .isInstanceOf(GoldSetDatasetValidationException.class)
                .satisfies(error -> assertThat(((GoldSetDatasetValidationException) error).violations())
                        .containsExactly(
                                "duplicate query_id: q1",
                                "blank question for query_id: q1",
                                "duplicate chunk_id: c1",
                                "query_id q1 references missing chunk_id: missing-chunk"
                        ))
                .hasMessageContaining("4 violation(s)")
                .hasMessageContaining("duplicate query_id: q1")
                .hasMessageContaining("missing chunk_id: missing-chunk");
    }

    @Test
    void rejectsChunkWithoutContent() {
        List<GoldSetCase> cases = List.of(goldCase("q1", "Redis", List.of("c1")));
        GoldSetChunk blank = new GoldSetChunk("c1", "doc", 0, "  ", "", "", "");

        assertThatThrownBy(() -> validator.validate(cases, List.of(blank)))
                .isInstanceOf(GoldSetDatasetValidationException.class)
                .hasMessageContaining("blank content for chunk_id: c1");
    }

    @Test
    void rejectsCaseWithoutGroundTruth() {
        List<GoldSetCase> cases = List.of(goldCase("q1", "Redis persistence", List.of()));

        assertThatThrownBy(() -> validator.validate(cases, List.of(chunk("c1"))))
                .isInstanceOf(GoldSetDatasetValidationException.class)
                .hasMessageContaining("empty ground_truth for query_id: q1");
    }

    private GoldSetCase goldCase(String queryId, String question, List<String> groundTruth) {
        return new GoldSetCase(queryId, question, groundTruth, Map.of(), "", "", "cache");
    }

    private GoldSetChunk chunk(String chunkId) {
        return new GoldSetChunk(chunkId, "doc", 0, "content", "title", "cache", "");
    }
}

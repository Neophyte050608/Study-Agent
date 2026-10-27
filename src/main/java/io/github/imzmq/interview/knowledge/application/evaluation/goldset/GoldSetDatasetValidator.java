package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates chunk-level benchmark identities, content, and references before retrieval. */
public final class GoldSetDatasetValidator {

    public void validate(List<GoldSetCase> cases, List<GoldSetChunk> chunks) {
        List<GoldSetCase> safeCases = cases == null ? List.of() : cases;
        List<GoldSetChunk> safeChunks = chunks == null ? List.of() : chunks;
        List<String> violations = new ArrayList<>();

        Set<String> queryIds = new HashSet<>();
        Set<String> reportedQueryIds = new HashSet<>();
        for (GoldSetCase goldCase : safeCases) {
            if (!queryIds.add(goldCase.queryId()) && reportedQueryIds.add(goldCase.queryId())) {
                violations.add("duplicate query_id: " + goldCase.queryId());
            }
            if (goldCase.question() == null || goldCase.question().isBlank()) {
                violations.add("blank question for query_id: " + goldCase.queryId());
            }
            if (goldCase.groundTruth() == null || goldCase.groundTruth().isEmpty()) {
                violations.add("empty ground_truth for query_id: " + goldCase.queryId());
            }
        }

        Set<String> chunkIds = new HashSet<>();
        Set<String> reportedChunkIds = new HashSet<>();
        for (GoldSetChunk chunk : safeChunks) {
            if (!chunkIds.add(chunk.chunkId()) && reportedChunkIds.add(chunk.chunkId())) {
                violations.add("duplicate chunk_id: " + chunk.chunkId());
            }
            if (chunk.content() == null || chunk.content().isBlank()) {
                violations.add("blank content for chunk_id: " + chunk.chunkId());
            }
        }

        for (GoldSetCase goldCase : safeCases) {
            if (goldCase.groundTruth() == null) {
                continue;
            }
            for (String expectedChunkId : goldCase.groundTruth()) {
                if (!chunkIds.contains(expectedChunkId)) {
                    violations.add("query_id " + goldCase.queryId()
                            + " references missing chunk_id: " + expectedChunkId);
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new GoldSetDatasetValidationException(violations);
        }
    }
}

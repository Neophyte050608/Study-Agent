package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** One BM25-ranked corpus chunk. */
public record GoldSetBm25Match(String chunkId, double score) {
}

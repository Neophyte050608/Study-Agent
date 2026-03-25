package com.example.interview.ingestion;

public enum IngestionNodeStage {
    FETCH,
    PARSE,
    ENHANCE,
    CHUNK,
    EMBED_INDEX,
    LEXICAL_INDEX,
    GRAPH_SYNC,
    SYNC_MARK,
    LEGACY_SYNC
}

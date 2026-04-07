package com.example.interview.service;

/**
 * 本地知识图链路失败原因。
 *
 * <p>第一阶段先固定失败语义，第二阶段再逐步接入真正的本地检索实现。</p>
 */
public enum LocalGraphFailureReason {
    INDEX_NOT_CONFIGURED,
    INDEX_NOT_FOUND,
    INDEX_LOAD_FAILED,
    INDEX_SCHEMA_MISMATCH,
    INDEX_EMPTY,
    CANDIDATE_RECALL_EMPTY,
    OLLAMA_UNAVAILABLE,
    OLLAMA_TIMEOUT,
    OLLAMA_INVALID_JSON,
    ROUTING_EMPTY,
    NOTE_FILE_NOT_FOUND,
    NOTE_FILE_OUTSIDE_VAULT,
    NOTE_PARSE_FAILED,
    CONTEXT_TOO_THIN,
    LOCAL_RETRIEVAL_NOT_IMPLEMENTED
}

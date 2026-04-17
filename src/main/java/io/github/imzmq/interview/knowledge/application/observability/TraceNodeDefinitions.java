package io.github.imzmq.interview.knowledge.application.observability;

public final class TraceNodeDefinitions {

    public static final TraceNodeDefinition CHAT_STREAM_ROOT = new TraceNodeDefinition("ROOT", "CHAT_STREAM");
    public static final TraceNodeDefinition PRE_FILTER = new TraceNodeDefinition("INTENT", "PRE_FILTER");
    public static final TraceNodeDefinition DOMAIN_CLASSIFY = new TraceNodeDefinition("INTENT", "DOMAIN_CLASSIFY");
    public static final TraceNodeDefinition INTENT_ROUTE = new TraceNodeDefinition("INTENT", "INTENT_ROUTE");
    public static final TraceNodeDefinition SLOT_REFINE = new TraceNodeDefinition("INTENT", "SLOT_REFINE");
    public static final TraceNodeDefinition REACT_ROUTE_LLM = new TraceNodeDefinition("ROUTING", "REACT_ROUTE_LLM");
    public static final TraceNodeDefinition QUERY_REWRITE = new TraceNodeDefinition("RETRIEVAL", "QUERY_REWRITE");
    public static final TraceNodeDefinition DOC_RETRIEVE = new TraceNodeDefinition("RETRIEVAL", "DOC_RETRIEVE");
    public static final TraceNodeDefinition RETRIEVAL_CACHE_HIT = new TraceNodeDefinition("RETRIEVAL", "RETRIEVAL_CACHE_HIT");
    public static final TraceNodeDefinition LOCAL_GRAPH_RETRIEVE = new TraceNodeDefinition("RETRIEVAL", "LOCAL_GRAPH_RETRIEVE");
    public static final TraceNodeDefinition HYBRID_FUSION_RETRIEVE = new TraceNodeDefinition("RETRIEVAL", "HYBRID_FUSION_RETRIEVE");
    public static final TraceNodeDefinition IMAGE_ASSOC_RETRIEVE = new TraceNodeDefinition("RETRIEVAL", "IMAGE_ASSOC_RETRIEVE");
    public static final TraceNodeDefinition IMAGE_SEMANTIC_RETRIEVE = new TraceNodeDefinition("RETRIEVAL", "IMAGE_SEMANTIC_RETRIEVE");
    public static final TraceNodeDefinition WEB_FALLBACK = new TraceNodeDefinition("RETRIEVAL", "WEB_FALLBACK");
    public static final TraceNodeDefinition LOCAL_GRAPH_FALLBACK = new TraceNodeDefinition("LOCAL_GRAPH_FALLBACK", "LOCAL_GRAPH_FALLBACK");
    public static final TraceNodeDefinition LOCAL_INDEX_LOAD = new TraceNodeDefinition("LOCAL_INDEX_LOAD", "LOCAL_INDEX_LOAD");
    public static final TraceNodeDefinition LOCAL_CANDIDATE_RECALL = new TraceNodeDefinition("LOCAL_CANDIDATE_RECALL", "LOCAL_CANDIDATE_RECALL");
    public static final TraceNodeDefinition LOCAL_OLLAMA_ROUTE = new TraceNodeDefinition("LOCAL_OLLAMA_ROUTE", "LOCAL_OLLAMA_ROUTE");
    public static final TraceNodeDefinition LOCAL_NOTE_GRAPH = new TraceNodeDefinition("LOCAL_NOTE_GRAPH", "LOCAL_NOTE_GRAPH");
    public static final TraceNodeDefinition LLM_STREAM = new TraceNodeDefinition("GENERATION", "LLM_STREAM");
    public static final TraceNodeDefinition LLM_FIRST_TOKEN = new TraceNodeDefinition("GENERATION", "LLM_FIRST_TOKEN");
    public static final TraceNodeDefinition LLM_EVALUATION = new TraceNodeDefinition("GENERATION", "LLM_EVALUATION");
    public static final TraceNodeDefinition STREAM_DISPATCH = new TraceNodeDefinition("STREAM", "STREAM_DISPATCH");
    public static final TraceNodeDefinition FINISH = new TraceNodeDefinition("TERMINAL", "FINISH");
    public static final TraceNodeDefinition CANCEL = new TraceNodeDefinition("TERMINAL", "CANCEL");
    public static final TraceNodeDefinition ERROR = new TraceNodeDefinition("TERMINAL", "ERROR");

    private TraceNodeDefinitions() {
    }
}






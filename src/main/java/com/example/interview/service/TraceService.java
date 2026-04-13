package com.example.interview.service;

import java.util.Map;

public interface TraceService {

    TraceNodeHandle startRoot(String traceId, TraceNodeDefinition definition, Map<String, Object> attributes);

    TraceNodeHandle startChild(String traceId, String parentNodeId, TraceNodeDefinition definition, Map<String, Object> attributes);

    void success(TraceNodeHandle handle, Map<String, Object> result);

    void fail(TraceNodeHandle handle, String errorMessage, Map<String, Object> result);
}

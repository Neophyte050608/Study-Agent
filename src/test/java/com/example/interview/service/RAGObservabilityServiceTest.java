package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RAGObservabilityServiceTest {

    @Test
    void shouldUseStructuredRetrievedDocsMetricInOverview() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);

        service.startNode("trace-1", "root-1", null, "ROOT", "Task Dispatch");
        service.startNode("trace-1", "retrieval-1", "root-1", "RETRIEVAL", "Hybrid Retrieval");
        service.endNode(
                "trace-1",
                "retrieval-1",
                "question",
                "legacy summary",
                null,
                new RAGObservabilityService.NodeMetrics(7, false)
        );
        service.endNode("trace-1", "root-1", "input", "output", null);

        Map<String, Object> overview = service.getOverview();
        assertEquals("7.0", overview.get("avgRetrievedDocs"));
    }

    @Test
    void shouldUseRootNodeDurationInsteadOfFirstInsertedNode() throws Exception {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);

        service.startNode("trace-2", "child-1", "root-2", "RETRIEVAL", "Hybrid Retrieval");
        Thread.sleep(10L);
        service.startNode("trace-2", "root-2", null, "ROOT", "Task Dispatch");
        Thread.sleep(10L);
        service.endNode("trace-2", "child-1", "input", "3 docs retrieved", null);
        Thread.sleep(10L);
        service.endNode("trace-2", "root-2", "input", "output", null);

        RAGObservabilityService.RAGTrace trace = service.getTraceDetail("trace-2");
        assertNotNull(trace);
        RAGObservabilityService.RAGTraceNode rootNode = trace.findNode("root-2");
        RAGObservabilityService.RAGTraceNode childNode = trace.findNode("child-1");
        assertNotNull(rootNode);
        assertNotNull(childNode);
        assertEquals(rootNode.durationMs(), trace.getDurationMs());
        assertNotEquals(childNode.durationMs(), trace.getDurationMs());
    }
}

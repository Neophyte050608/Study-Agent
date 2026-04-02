package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.entity.RagTraceDO;
import com.example.interview.entity.RagTraceNodeDO;
import com.example.interview.mapper.RagTraceMapper;
import com.example.interview.mapper.RagTraceNodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertNotNull(overview.get("p95LatencyMs"));
        assertNotNull(overview.get("successRate"));
        assertNotNull(overview.get("failedTraceCount"));
    }

    @Test
    void shouldListActiveTraceWhileRootNotFinished() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);

        service.startNode("trace-active", "root-active", null, "ROOT", "Task Dispatch");
        service.startNode("trace-active", "child-active", "root-active", "RETRIEVAL", "Hybrid Retrieval");
        service.endNode("trace-active", "child-active", "input", "3 docs retrieved", null);

        List<RAGObservabilityService.RAGTrace> active = service.listActive(20);
        assertEquals(1, active.size());
        assertEquals("trace-active", active.get(0).traceId());

        service.endNode("trace-active", "root-active", "input", "output", null);
        assertTrue(service.listActive(20).isEmpty());
    }

    @Test
    void shouldPublishNodeAndTraceEventsWhenEventBusEnabled() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RagTraceEventBus eventBus = mock(RagTraceEventBus.class);
        RAGObservabilityService service = new RAGObservabilityService(properties, null, null, eventBus);

        service.startNode("trace-event", "root-event", null, "ROOT", "Task Dispatch");
        service.startNode("trace-event", "child-event", "root-event", "RETRIEVAL", "Hybrid Retrieval");
        service.endNode("trace-event", "child-event", "input", "2 docs retrieved", null);
        service.endNode("trace-event", "root-event", "input", "output", null);

        verify(eventBus, atLeastOnce()).publish(any(), any(RagTraceEventBus.RagTraceStreamEvent.class));
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

    @Test
    void shouldKeepTraceActiveUntilRootNodeCompleted() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);

        service.startNode("trace-3", "root-3", null, "ROOT", "Task Dispatch");
        service.startNode("trace-3", "child-3", "root-3", "RETRIEVAL", "Hybrid Retrieval");
        service.endNode("trace-3", "child-3", "input", "3 docs retrieved", null);

        RAGObservabilityService.RAGTrace activeTrace = service.getTraceDetail("trace-3");
        assertNotNull(activeTrace);
        assertEquals(2, activeTrace.nodes().size());
        assertEquals("RUNNING", activeTrace.findNode("root-3").status());
        assertEquals("COMPLETED", activeTrace.findNode("child-3").status());
        assertTrue(service.listRecent(10).isEmpty());

        service.endNode("trace-3", "root-3", "input", "output", null);

        assertNull(service.getTraceDetail("missing-trace"));
        assertEquals(1, service.listRecent(10).size());
    }

    @Test
    void shouldPersistCompletedTraceWhenRootNodeFinished() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RagTraceMapper traceMapper = mock(RagTraceMapper.class);
        RagTraceNodeMapper traceNodeMapper = mock(RagTraceNodeMapper.class);
        when(traceMapper.selectOne(any())).thenReturn(null);
        RAGObservabilityService service = new RAGObservabilityService(properties, traceMapper, traceNodeMapper);

        service.startNode("trace-db", "root-db", null, "ROOT", "Task Dispatch");
        service.startNode("trace-db", "retrieval-db", "root-db", "RETRIEVAL", "Hybrid Retrieval");
        service.endNode("trace-db", "retrieval-db", "input", "legacy summary", null, new RAGObservabilityService.NodeMetrics(5, true));
        service.endNode("trace-db", "root-db", "input", "output", null);

        ArgumentCaptor<RagTraceDO> traceCaptor = ArgumentCaptor.forClass(RagTraceDO.class);
        verify(traceMapper).insert(traceCaptor.capture());
        verify(traceNodeMapper).delete(any());
        verify(traceNodeMapper, times(2)).insert(any(RagTraceNodeDO.class));

        RagTraceDO persistedTrace = traceCaptor.getValue();
        assertEquals("trace-db", persistedTrace.getTraceId());
        assertEquals("COMPLETED", persistedTrace.getTraceStatus());
        assertEquals(1, persistedTrace.getRetrievalNodeCount());
        assertEquals(5, persistedTrace.getTotalRetrievedDocs());
        assertTrue(Boolean.TRUE.equals(persistedTrace.getWebFallbackUsed()));
    }

    @Test
    void shouldLoadTraceDetailFromPersistenceWhenMemoryMiss() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RagTraceMapper traceMapper = mock(RagTraceMapper.class);
        RagTraceNodeMapper traceNodeMapper = mock(RagTraceNodeMapper.class);
        RAGObservabilityService service = new RAGObservabilityService(properties, traceMapper, traceNodeMapper);

        RagTraceDO traceDO = new RagTraceDO();
        traceDO.setTraceId("trace-persisted");
        traceDO.setStartedAt(LocalDateTime.now().minusSeconds(5));
        when(traceMapper.selectOne(any())).thenReturn(traceDO);

        RagTraceNodeDO rootNode = new RagTraceNodeDO();
        rootNode.setTraceId("trace-persisted");
        rootNode.setNodeId("root-persisted");
        rootNode.setNodeType("ROOT");
        rootNode.setNodeName("Task Dispatch");
        rootNode.setNodeStatus("COMPLETED");
        rootNode.setStartedAt(LocalDateTime.now().minusSeconds(5));
        rootNode.setEndedAt(LocalDateTime.now().minusSeconds(1));
        rootNode.setDurationMs(4000L);

        RagTraceNodeDO retrievalNode = new RagTraceNodeDO();
        retrievalNode.setTraceId("trace-persisted");
        retrievalNode.setNodeId("retrieval-persisted");
        retrievalNode.setParentNodeId("root-persisted");
        retrievalNode.setNodeType("RETRIEVAL");
        retrievalNode.setNodeName("Hybrid Retrieval");
        retrievalNode.setNodeStatus("COMPLETED");
        retrievalNode.setRetrievedDocs(6);
        retrievalNode.setWebFallbackUsed(false);
        retrievalNode.setStartedAt(LocalDateTime.now().minusSeconds(4));
        retrievalNode.setEndedAt(LocalDateTime.now().minusSeconds(2));
        retrievalNode.setDurationMs(2000L);
        when(traceNodeMapper.selectList(any())).thenReturn(List.of(rootNode, retrievalNode));

        RAGObservabilityService.RAGTrace trace = service.getTraceDetail("trace-persisted");

        assertNotNull(trace);
        assertEquals("trace-persisted", trace.traceId());
        assertNotNull(trace.findNode("root-persisted"));
        assertEquals(6, trace.findNode("retrieval-persisted").metrics().retrievedDocs());
    }
}

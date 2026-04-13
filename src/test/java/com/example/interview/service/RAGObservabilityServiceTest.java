package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.entity.RagTraceDO;
import com.example.interview.entity.RagTraceNodeDO;
import com.example.interview.mapper.RagTraceMapper;
import com.example.interview.mapper.RagTraceNodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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
    void shouldAggregateRiskAndActiveCountsInOverview() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);

        service.startNode("trace-risk", "root-risk", null, "ROOT", "Task Dispatch");
        service.startNode("trace-risk", "fallback-node", "root-risk", "RETRIEVAL", "WEB_FALLBACK");
        service.endNode(
                "trace-risk",
                "fallback-node",
                "question",
                "fallback output",
                null,
                new RAGObservabilityService.NodeMetrics(0, true),
                new RAGObservabilityService.NodeDetails(
                        1,
                        "RAG_ONLY",
                        null,
                        false,
                        0,
                        List.of(),
                        1,
                        0,
                        "WEB_SEARCH",
                        null,
                        null,
                        null
                )
        );
        service.endNode("trace-risk", "root-risk", "input", "output", null);

        service.startNode("trace-active-overview", "root-active-overview", null, "ROOT", "Task Dispatch");

        Map<String, Object> overview = service.getOverview();

        assertEquals(1, overview.get("activeTraceCount"));
        assertEquals(1L, overview.get("riskyTraceCount"));
        assertEquals(1L, overview.get("fallbackTraceCount"));
        assertEquals(1L, overview.get("emptyRetrievalTraceCount"));
        assertEquals(1L, ((Map<?, ?>) overview.get("statusCounts")).get("SUCCESS"));
        assertEquals(1L, ((Map<?, ?>) overview.get("riskTagCounts")).get("fallback_triggered"));
        assertEquals(1L, ((Map<?, ?>) overview.get("riskTagCounts")).get("retrieval_empty"));
        Object trendBuckets = overview.get("trendBuckets");
        assertTrue(trendBuckets instanceof List<?>);
        assertTrue(!((List<?>) trendBuckets).isEmpty());
        assertEquals("INFO", overview.get("alertLevel"));
        assertTrue(((List<?>) overview.get("alertTags")).contains("active_traces_present"));
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
    void activeSummaryShouldRemainRunningBeforeRootFinished() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);

        service.startNode("trace-active-summary", "root-active-summary", null, "ROOT", "Task Dispatch");
        service.startNode("trace-active-summary", "child-active-summary", "root-active-summary", "RETRIEVAL", "Hybrid Retrieval");
        service.endNode("trace-active-summary", "child-active-summary", "input", "3 docs retrieved", null);

        List<RAGObservabilityService.TraceSummary> summaries = service.listActiveSummaries(10);

        assertEquals(1, summaries.size());
        assertEquals("RUNNING", summaries.get(0).traceStatus());
        assertTrue(summaries.get(0).riskTags().stream().noneMatch("retrieval_empty"::equals));
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
    void shouldUseBusinessNodeWindowInsteadOfRootNodeConnectionLifetime() {
        Instant base = Instant.parse("2026-04-13T13:00:00Z");
        RAGObservabilityService.RAGTraceNode rootNode = RAGObservabilityService.RAGTraceNode.restore(
                "root-connection",
                null,
                "ROOT",
                "CHAT_STREAM",
                base,
                base.plusMillis(260_388),
                "",
                "",
                null,
                null,
                "COMPLETED"
        );
        RAGObservabilityService.RAGTraceNode retrievalNode = RAGObservabilityService.RAGTraceNode.restore(
                "retrieval-node",
                "root-connection",
                "RETRIEVAL",
                "DOC_RETRIEVE",
                base.plusMillis(300),
                base.plusMillis(1_800),
                "",
                "",
                null,
                new RAGObservabilityService.NodeMetrics(5, false),
                "COMPLETED"
        );
        RAGObservabilityService.RAGTraceNode generationNode = RAGObservabilityService.RAGTraceNode.restore(
                "generation-node",
                "root-connection",
                "GENERATION",
                "LLM_STREAM",
                base.plusMillis(2_000),
                base.plusMillis(7_800),
                "",
                "",
                null,
                null,
                "COMPLETED"
        );
        RAGObservabilityService.RAGTraceNode terminalNode = RAGObservabilityService.RAGTraceNode.restore(
                "terminal-node",
                "root-connection",
                "TERMINAL",
                "FINISH",
                base.plusMillis(7_900),
                base.plusMillis(8_000),
                "",
                "",
                null,
                null,
                "COMPLETED"
        );
        RAGObservabilityService.RAGTrace trace = new RAGObservabilityService.RAGTrace(
                "trace-window",
                base,
                new java.util.ArrayList<>(List.of(rootNode, retrievalNode, generationNode, terminalNode))
        );

        assertEquals(7_500L, trace.getDurationMs());
        assertEquals(base.plusMillis(300), trace.getEffectiveStartTime());
        assertEquals(base.plusMillis(7_800), trace.getEffectiveEndTime());
        assertTrue(rootNode.durationMs() > trace.getDurationMs());
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
        assertEquals(childNode.durationMs(), trace.getDurationMs());
        assertTrue(rootNode.startTime().isAfter(childNode.startTime()));
        assertEquals(childNode.startTime(), trace.getEffectiveStartTime());
    }

    @Test
    void shouldFallbackToRootDurationWhenNoBusinessChildExists() {
        Instant base = Instant.parse("2026-04-13T14:00:00Z");
        RAGObservabilityService.RAGTraceNode rootNode = RAGObservabilityService.RAGTraceNode.restore(
                "root-only",
                null,
                "ROOT",
                "CHAT_STREAM",
                base,
                base.plusMillis(1_200),
                "",
                "",
                null,
                null,
                "COMPLETED"
        );
        RAGObservabilityService.RAGTraceNode terminalNode = RAGObservabilityService.RAGTraceNode.restore(
                "terminal-only",
                "root-only",
                "TERMINAL",
                "FINISH",
                base.plusMillis(1_100),
                base.plusMillis(1_150),
                "",
                "",
                null,
                null,
                "COMPLETED"
        );
        RAGObservabilityService.RAGTrace trace = new RAGObservabilityService.RAGTrace(
                "trace-root-only",
                base,
                new java.util.ArrayList<>(List.of(rootNode, terminalNode))
        );

        assertEquals(1_200L, trace.getDurationMs());
        assertEquals(base, trace.getEffectiveStartTime());
        assertEquals(base.plusMillis(1_200), trace.getEffectiveEndTime());
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

    @Test
    void shouldRestoreStructuredDetailsFromPersistedOutputSummary() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RagTraceMapper traceMapper = mock(RagTraceMapper.class);
        RagTraceNodeMapper traceNodeMapper = mock(RagTraceNodeMapper.class);
        RAGObservabilityService service = new RAGObservabilityService(properties, traceMapper, traceNodeMapper);

        RagTraceDO traceDO = new RagTraceDO();
        traceDO.setTraceId("trace-details");
        traceDO.setStartedAt(LocalDateTime.now().minusSeconds(5));
        when(traceMapper.selectOne(any())).thenReturn(traceDO);

        RagTraceNodeDO retrievalNode = new RagTraceNodeDO();
        retrievalNode.setTraceId("trace-details");
        retrievalNode.setNodeId("retrieval-details");
        retrievalNode.setParentNodeId("root-details");
        retrievalNode.setNodeType("RETRIEVAL");
        retrievalNode.setNodeName("LOCAL_GRAPH_RETRIEVE");
        retrievalNode.setNodeStatus("COMPLETED");
        retrievalNode.setRetrievedDocs(10);
        retrievalNode.setWebFallbackUsed(false);
        retrievalNode.setOutputSummary("Local Graph Retrieve{mode=HYBRID_FUSION, cacheHit=false, docCount=10, retrievedDocs=10, docRefs=[[primary] Java内存模型 | Backend/JVM/JMM.md, [linked] volatile | Backend/JUC/volatile.md]}");
        retrievalNode.setStartedAt(LocalDateTime.now().minusSeconds(4));
        retrievalNode.setEndedAt(LocalDateTime.now().minusSeconds(2));
        when(traceNodeMapper.selectList(any())).thenReturn(List.of(retrievalNode));

        RAGObservabilityService.RAGTrace trace = service.getTraceDetail("trace-details");

        assertNotNull(trace);
        RAGObservabilityService.RAGTraceNode node = trace.findNode("retrieval-details");
        assertNotNull(node);
        assertNotNull(node.details());
        assertEquals("HYBRID_FUSION", node.details().retrievalMode());
        assertEquals(10, node.details().retrievedDocCount());
        assertEquals(2, node.details().retrievedDocumentRefs().size());
    }

    @Test
    void shouldExposeFirstTokenAndStreamDispatchMetricsInSummary() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);
        DefaultTraceService traceService = new DefaultTraceService(service, new TraceAttributeSanitizer());

        service.startNode("trace-stream-summary", "root-stream-summary", null, "ROOT", "CHAT_STREAM");
        TraceNodeHandle llmStream = traceService.startChild(
                "trace-stream-summary",
                "root-stream-summary",
                TraceNodeDefinitions.LLM_STREAM,
                Map.of("status", "RUNNING")
        );
        TraceNodeHandle firstToken = traceService.startChild(
                "trace-stream-summary",
                llmStream.nodeId(),
                TraceNodeDefinitions.LLM_FIRST_TOKEN,
                Map.of("status", "RUNNING")
        );
        traceService.success(firstToken, Map.of("firstTokenMs", 123L, "status", "COMPLETED"));
        TraceNodeHandle streamDispatch = traceService.startChild(
                "trace-stream-summary",
                "root-stream-summary",
                TraceNodeDefinitions.STREAM_DISPATCH,
                Map.of("status", "RUNNING")
        );
        traceService.success(streamDispatch, Map.of("completionMs", 45L, "chunkCount", 3, "status", "COMPLETED"));
        traceService.success(llmStream, Map.of("completionMs", 456L, "status", "COMPLETED"));
        service.endNode("trace-stream-summary", "root-stream-summary", "input", "output", null);

        RAGObservabilityService.TraceDetailView detailView = service.getTraceDetailView("trace-stream-summary");

        assertNotNull(detailView);
        assertNotNull(detailView.summary());
        assertEquals(123L, detailView.summary().firstTokenMs());
        assertEquals(45L, detailView.summary().streamDispatchMs());
    }

    @Test
    void shouldGenerateRiskTagsInSummary() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService service = new RAGObservabilityService(properties);
        DefaultTraceService traceService = new DefaultTraceService(service, new TraceAttributeSanitizer());

        service.startNode("trace-risk-summary", "root-risk-summary", null, "ROOT", "CHAT_STREAM");
        TraceNodeHandle retrieval = traceService.startChild(
                "trace-risk-summary",
                "root-risk-summary",
                TraceNodeDefinitions.RETRIEVAL_CACHE_HIT,
                Map.of("status", "RUNNING")
        );
        traceService.success(retrieval, Map.of("docCount", 0, "status", "COMPLETED"));
        TraceNodeHandle firstToken = traceService.startChild(
                "trace-risk-summary",
                "root-risk-summary",
                TraceNodeDefinitions.LLM_FIRST_TOKEN,
                Map.of("status", "RUNNING")
        );
        traceService.success(firstToken, Map.of("firstTokenMs", 5000L, "status", "COMPLETED"));
        TraceNodeHandle fallback = traceService.startChild(
                "trace-risk-summary",
                "root-risk-summary",
                TraceNodeDefinitions.LOCAL_GRAPH_FALLBACK,
                Map.of("status", "RUNNING")
        );
        traceService.fail(fallback, "fallback", Map.of("fallbackReason", "CONTEXT_TOO_THIN", "status", "FAILED"));
        service.endNode("trace-risk-summary", "root-risk-summary", "input", "output", null);

        RAGObservabilityService.TraceDetailView detailView = service.getTraceDetailView("trace-risk-summary");

        assertNotNull(detailView);
        assertNotNull(detailView.summary());
        assertTrue(detailView.summary().riskTags().contains("slow_first_token"));
        assertTrue(detailView.summary().riskTags().contains("fallback_triggered"));
        assertTrue(detailView.summary().riskTags().contains("retrieval_empty"));
        assertEquals(detailView.summary().riskTags().size(), detailView.summary().riskCount());
    }
}

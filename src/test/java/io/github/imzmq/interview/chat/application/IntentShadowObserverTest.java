package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.config.routing.RoutingUnderstandingProperties;
import io.github.imzmq.interview.observability.application.trace.TraceNodeDefinitions;
import io.github.imzmq.interview.observability.application.trace.TraceNodeHandle;
import io.github.imzmq.interview.observability.application.trace.TraceService;
import io.github.imzmq.interview.routing.application.contract.UnderstandingService;
import io.github.imzmq.interview.routing.application.contract.result.DecisionSource;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import io.github.imzmq.interview.routing.application.contract.result.IntentTask;
import io.github.imzmq.interview.routing.application.contract.result.TurnRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentShadowObserverTest {

    private final TraceNodeHandle root = new TraceNodeHandle(
            "trace-1", "root-1", "", TraceNodeDefinitions.CHAT_STREAM_ROOT);
    private final TraceNodeHandle child = new TraceNodeHandle(
            "trace-1", "intent-shadow-1", "root-1", TraceNodeDefinitions.INTENT_SHADOW);

    @Test
    void disabledShadowDoesNotCallUnderstandingOrCreateTrace() {
        UnderstandingService understandingService = mock(UnderstandingService.class);
        TraceService traceService = mock(TraceService.class);
        RoutingUnderstandingProperties properties = properties(false);
        IntentShadowObserver observer = new IntentShadowObserver(understandingService, properties, traceService);

        observer.observe("session", "user", "query", "history", root);
        properties.setEnabled(true);
        observer.observe("session", "user", "query", "history", null);

        verifyNoInteractions(understandingService, traceService);
    }

    @Test
    void enabledShadowPassesInputsAndCompletesTraceWithoutAffectingCaller() {
        UnderstandingService understandingService = mock(UnderstandingService.class);
        TraceService traceService = mock(TraceService.class);
        RoutingUnderstandingProperties properties = properties(true);
        IntentRecord record = new IntentRecord(
                "session", "query", TurnRelation.FOLLOW_UP, "standalone",
                List.of(new IntentTask("knowledge.qa", Map.of("topic", "JVM"), 0.8)),
                0.9, "context continues", DecisionSource.LLM);
        when(traceService.startChild("trace-1", "root-1", TraceNodeDefinitions.INTENT_SHADOW, Map.of(
                "sessionId", "session", "userId", "user", "status", "RUNNING")))
                .thenReturn(child);
        when(understandingService.understand("session", "user", "query", "history"))
                .thenReturn(CompletableFuture.completedFuture(record));
        IntentShadowObserver observer = new IntentShadowObserver(understandingService, properties, traceService);

        assertThatCode(() -> observer.observe("session", "user", "query", "history", root))
                .doesNotThrowAnyException();

        verify(understandingService).understand("session", "user", "query", "history");
        verify(traceService).success(child, Map.of(
                "relation", "FOLLOW_UP",
                "queryChanged", true,
                "standaloneQueryLength", 10,
                "taskIds", List.of("knowledge.qa"),
                "taskCount", 1,
                "confidence", 0.9,
                "source", "LLM",
                "status", "COMPLETED"));
    }

    @Test
    void incompleteUnderstandingReturnsImmediatelyAndCompletesTraceLater() {
        UnderstandingService understandingService = mock(UnderstandingService.class);
        TraceService traceService = mock(TraceService.class);
        RoutingUnderstandingProperties properties = properties(true);
        CompletableFuture<IntentRecord> pending = new CompletableFuture<>();
        when(traceService.startChild("trace-1", "root-1", TraceNodeDefinitions.INTENT_SHADOW, Map.of(
                "sessionId", "session", "userId", "user", "status", "RUNNING")))
                .thenReturn(child);
        when(understandingService.understand("session", "user", "query", "history")).thenReturn(pending);
        IntentShadowObserver observer = new IntentShadowObserver(understandingService, properties, traceService);

        observer.observe("session", "user", "query", "history", root);

        verify(traceService, never()).success(org.mockito.ArgumentMatchers.any(), anyMap());
        pending.complete(IntentRecord.fallback("session", "query", "fallback"));
        verify(traceService).success(org.mockito.ArgumentMatchers.eq(child), anyMap());
    }

    @Test
    void asynchronousAndSynchronousFailuresOnlyFailTrace() {
        UnderstandingService asyncFailure = mock(UnderstandingService.class);
        TraceService asyncTrace = mock(TraceService.class);
        when(asyncTrace.startChild("trace-1", "root-1", TraceNodeDefinitions.INTENT_SHADOW, Map.of(
                "sessionId", "session", "userId", "user", "status", "RUNNING")))
                .thenReturn(child);
        when(asyncFailure.understand("session", "user", "query", "history"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("async boom")));

        IntentShadowObserver asyncObserver = new IntentShadowObserver(asyncFailure, properties(true), asyncTrace);
        assertThatCode(() -> asyncObserver.observe("session", "user", "query", "history", root))
                .doesNotThrowAnyException();
        verify(asyncTrace).fail(child, "intent_shadow_failed", Map.of("status", "FAILED", "errorType", "IllegalStateException"));

        UnderstandingService syncFailure = mock(UnderstandingService.class);
        TraceService syncTrace = mock(TraceService.class);
        when(syncTrace.startChild("trace-1", "root-1", TraceNodeDefinitions.INTENT_SHADOW, Map.of(
                "sessionId", "session", "userId", "user", "status", "RUNNING")))
                .thenReturn(child);
        when(syncFailure.understand("session", "user", "query", "history"))
                .thenThrow(new IllegalArgumentException("sync boom"));

        IntentShadowObserver syncObserver = new IntentShadowObserver(syncFailure, properties(true), syncTrace);
        assertThatCode(() -> syncObserver.observe("session", "user", "query", "history", root))
                .doesNotThrowAnyException();
        verify(syncTrace).fail(child, "intent_shadow_failed", Map.of("status", "FAILED", "errorType", "IllegalArgumentException"));
    }

    private RoutingUnderstandingProperties properties(boolean enabled) {
        RoutingUnderstandingProperties properties = new RoutingUnderstandingProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}

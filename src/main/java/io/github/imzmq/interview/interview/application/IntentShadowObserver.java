package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.config.routing.RoutingUnderstandingProperties;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeHandle;
import io.github.imzmq.interview.knowledge.application.observability.TraceService;
import io.github.imzmq.interview.routing.application.contract.UnderstandingService;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import io.github.imzmq.interview.routing.application.contract.result.IntentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

@Component
public class IntentShadowObserver {

    private static final Logger log = LoggerFactory.getLogger(IntentShadowObserver.class);

    private final UnderstandingService understandingService;
    private final RoutingUnderstandingProperties properties;
    private final TraceService traceService;

    public IntentShadowObserver(UnderstandingService understandingService,
                                RoutingUnderstandingProperties properties,
                                TraceService traceService) {
        this.understandingService = understandingService;
        this.properties = properties;
        this.traceService = traceService;
    }

    public void observe(String sessionId,
                        String userId,
                        String rawQuery,
                        String history,
                        TraceNodeHandle rootTrace) {
        if (!properties.isEnabled() || rootTrace == null) {
            return;
        }

        TraceNodeHandle shadowTrace = null;
        try {
            shadowTrace = traceService.startChild(
                    rootTrace.traceId(),
                    rootTrace.nodeId(),
                    TraceNodeDefinitions.INTENT_SHADOW,
                    Map.of(
                            "sessionId", safe(sessionId),
                            "userId", safe(userId),
                            "status", "RUNNING"
                    )
            );
            CompletionStage<IntentRecord> stage = understandingService.understand(
                    sessionId, userId, rawQuery, history);
            if (stage == null) {
                throw new IllegalStateException("understanding returned null stage");
            }
            TraceNodeHandle finalShadowTrace = shadowTrace;
            stage.whenComplete((record, error) -> complete(finalShadowTrace, record, error));
        } catch (Exception error) {
            failSafely(shadowTrace, error);
        }
    }

    private void complete(TraceNodeHandle shadowTrace, IntentRecord record, Throwable error) {
        if (error != null) {
            failSafely(shadowTrace, unwrap(error));
            return;
        }
        if (record == null) {
            failSafely(shadowTrace, new IllegalStateException("understanding returned null record"));
            return;
        }

        List<String> taskIds = record.tasks().stream()
                .map(IntentTask::intentLeafId)
                .toList();
        Map<String, Object> result = Map.of(
                "relation", record.relation().name(),
                "standaloneQuery", record.standaloneQuery(),
                "taskIds", taskIds,
                "taskCount", taskIds.size(),
                "confidence", record.confidence(),
                "source", record.source().name(),
                "status", "COMPLETED"
        );
        try {
            traceService.success(shadowTrace, result);
            log.info("intent shadow completed: traceId={}, relation={}, standaloneQuery={}, taskIds={}, taskCount={}, confidence={}, source={}",
                    shadowTrace.traceId(), record.relation(), record.standaloneQuery(), taskIds,
                    taskIds.size(), record.confidence(), record.source());
        } catch (Exception traceError) {
            log.debug("intent shadow success observation failed: traceId={}", shadowTrace.traceId(), traceError);
        }
    }

    private void failSafely(TraceNodeHandle shadowTrace, Throwable error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        if (shadowTrace != null) {
            try {
                traceService.fail(
                        shadowTrace,
                        message,
                        Map.of("status", "FAILED", "errorType", error.getClass().getSimpleName())
                );
            } catch (Exception traceError) {
                log.debug("intent shadow failure observation failed: traceId={}", shadowTrace.traceId(), traceError);
            }
        }
        log.debug("intent shadow failed without affecting legacy routing", error);
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

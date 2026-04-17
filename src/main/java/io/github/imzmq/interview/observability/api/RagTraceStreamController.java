package io.github.imzmq.interview.observability.api;

import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.observability.RagTraceEventBus;
import io.github.imzmq.interview.knowledge.application.observability.RagTraceEventBus.RagTraceStreamEvent;
import io.github.imzmq.interview.knowledge.application.observability.RagTraceEventBus.TraceSummary;
import io.github.imzmq.interview.stream.runtime.InterviewSseEmitterSender;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

/**
 * RAG Trace 实时事件流控制器（SSE）。
 *
 * 提供：GET /api/observability/rag-traces/{traceId}/stream
 * 用于订阅指定 traceId 的节点开始/结束与链路结束事件。
 */
@RestController
@RequestMapping("/api/observability/rag-traces")
public class RagTraceStreamController {

    private final RagTraceEventBus eventBus;
    private final RAGObservabilityService ragObservabilityService;

    public RagTraceStreamController(RagTraceEventBus eventBus, RAGObservabilityService ragObservabilityService) {
        this.eventBus = eventBus;
        this.ragObservabilityService = ragObservabilityService;
    }

    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("traceId") String traceId) {
        SseEmitter emitter = new SseEmitter(0L);
        InterviewSseEmitterSender sender = new InterviewSseEmitterSender(emitter);
        eventBus.subscribe(traceId, sender);

        // 连接回调：关闭时自动取消订阅
        emitter.onCompletion(() -> eventBus.unsubscribe(traceId, sender));
        emitter.onTimeout(() -> eventBus.unsubscribe(traceId, sender));
        emitter.onError(ex -> eventBus.unsubscribe(traceId, sender));

        // 连接建立后推送一个 trace_started 事件（包含当前摘要），便于前端初始化画布
        RAGObservabilityService.RAGTrace snapshot = ragObservabilityService.getTraceDetail(traceId);
        TraceSummary summary = snapshot == null
                ? new TraceSummary("RUNNING", 0L, 0, 0)
                : new TraceSummary("RUNNING", snapshot.getDurationMs(), snapshot.nodes().size(),
                (int) snapshot.nodes().stream().filter(n -> "RETRIEVAL".equals(n.getNodeType())).count());
        sender.sendEvent("trace_started", new RagTraceStreamEvent(
                traceId,
                "trace_started",
                LocalDateTime.now(),
                null,
                summary
        ));

        return emitter;
    }
}









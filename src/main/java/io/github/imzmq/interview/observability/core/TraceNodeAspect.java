package io.github.imzmq.interview.observability.core;

import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinition;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeHandle;
import io.github.imzmq.interview.knowledge.application.observability.TraceService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;

@Aspect
@Component
public class TraceNodeAspect {

    private final TraceService traceService;

    public TraceNodeAspect(TraceService traceService) {
        this.traceService = traceService;
    }

    @Around("@annotation(traceNode)")
    public Object around(ProceedingJoinPoint joinPoint, TraceNode traceNode) throws Throwable {
        String traceId = RAGTraceContext.getTraceId();
        String parentNodeId = RAGTraceContext.getCurrentNodeId();
        TraceNodeDefinition definition = new TraceNodeDefinition(traceNode.type(), traceNode.name());
        TraceNodeHandle handle = parentNodeId == null || parentNodeId.isBlank()
                ? traceService.startRoot(traceId, definition, Map.of())
                : traceService.startChild(traceId, parentNodeId, definition, Map.of());
        try {
            Object result = joinPoint.proceed();
            traceService.success(handle, Map.of("status", "COMPLETED"));
            return result;
        } catch (Throwable ex) {
            traceService.fail(handle, ex.getMessage(), Map.of("status", "FAILED"));
            throw ex;
        }
    }
}





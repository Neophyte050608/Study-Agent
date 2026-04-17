package io.github.imzmq.interview.knowledge.application.observability;

import io.github.imzmq.interview.stream.runtime.InterviewSseEmitterSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RAG Trace 事件总线（进程内发布/订阅）。
 *
 * 职责：
 * 1）维护每个 traceId 的订阅者列表（SSE 发送器），支持订阅与取消订阅；
 * 2）在节点开始/结束、链路结束/失败时发布事件到订阅者；
 * 3）确保发布过程线程安全，不影响主业务执行。
 */
@Service
public class RagTraceEventBus {

    /**
     * 订阅列表：traceId -> 订阅者（SSE 发送器）
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<InterviewSseEmitterSender>> subscribers = new ConcurrentHashMap<>();

    /**
     * 订阅指定 traceId 的事件流。
     *
     * @param traceId trace ID
     * @param sender  SSE 发送器
     */
    public void subscribe(String traceId, InterviewSseEmitterSender sender) {
        if (traceId == null || traceId.isBlank() || sender == null) {
            return;
        }
        subscribers.computeIfAbsent(traceId.trim(), k -> new CopyOnWriteArrayList<>()).add(sender);
    }

    /**
     * 取消订阅指定 traceId 的事件流。
     *
     * @param traceId trace ID
     * @param sender  SSE 发送器
     */
    public void unsubscribe(String traceId, InterviewSseEmitterSender sender) {
        if (traceId == null || traceId.isBlank() || sender == null) {
            return;
        }
        List<InterviewSseEmitterSender> list = subscribers.get(traceId.trim());
        if (list != null) {
            list.removeIf(s -> Objects.equals(s, sender));
            if (list.isEmpty()) {
                subscribers.remove(traceId.trim());
            }
        }
    }

    /**
     * 发布事件到订阅者。
     *
     * @param traceId trace ID
     * @param event   事件载荷
     */
    public void publish(String traceId, RagTraceStreamEvent event) {
        if (traceId == null || traceId.isBlank() || event == null) {
            return;
        }
        List<InterviewSseEmitterSender> list = subscribers.get(traceId.trim());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (InterviewSseEmitterSender sender : list) {
            try {
                sender.sendEvent(event.eventType(), event);
            } catch (Exception ex) {
                // 单个订阅者失败不影响整体发布；失败时主动取消订阅
                unsubscribe(traceId, sender);
            }
        }
    }

    /**
     * RAG Trace 流式事件模型。
     *
     * @param traceId   Trace ID
     * @param eventType 事件类型（trace_started/node_started/node_finished/trace_finished/trace_failed）
     * @param eventTime 事件时间
     * @param node      节点详情（可空）
     * @param summary   Trace 摘要（可空）
     */
    public record RagTraceStreamEvent(
            String traceId,
            String eventType,
            LocalDateTime eventTime,
            RAGObservabilityService.RAGTraceNode node,
            TraceSummary summary
    ) {
    }

    /**
     * Trace 摘要模型，用于快速汇总展示。
     *
     * @param traceStatus         链路状态（COMPLETED/FAILED/RUNNING）
     * @param durationMs          总耗时
     * @param nodeCount           节点总数
     * @param retrievalNodeCount  检索节点数量
     */
    public record TraceSummary(
            String traceStatus,
            Long durationMs,
            Integer nodeCount,
            Integer retrievalNodeCount
    ) {
    }
}






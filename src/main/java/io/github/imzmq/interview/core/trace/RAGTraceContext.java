package io.github.imzmq.interview.core.trace;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * RAG Trace 上下文管理器。
 * 使用 TransmittableThreadLocal (TTL) 在线程池异步执行中透传 Trace 信息。
 */
public final class RAGTraceContext {

    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>() {
        @Override
        protected Deque<String> initialValue() {
            return new ArrayDeque<>();
        }

        @Override
        public Deque<String> copy(Deque<String> parentValue) {
            return new ArrayDeque<>(parentValue);
        }
    };

    private RAGTraceContext() {
    }

    /**
     * 获取当前链路的 Trace ID。
     */
    public static String getTraceId() {
        String traceId = TRACE_ID.get();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            TRACE_ID.set(traceId);
        }
        return traceId;
    }

    /**
     * 设置当前链路的 Trace ID。
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    /**
     * 获取当前活跃的节点 ID（父节点）。
     */
    public static String getCurrentNodeId() {
        return NODE_STACK.get().peek();
    }

    /**
     * 进入一个新节点。
     */
    public static void pushNode(String nodeId) {
        NODE_STACK.get().push(nodeId);
    }

    /**
     * 退出当前节点。
     */
    public static String popNode() {
        return NODE_STACK.get().pop();
    }

    /**
     * 按节点 ID 安全退出节点。
     *
     * <p>优先弹出栈顶节点；若由于异常链路导致结束顺序不严格，
     * 则退化为从栈中移除指定节点，避免脏节点长期残留。</p>
     *
     * @param nodeId 待退出的节点 ID
     */
    public static void removeNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (nodeId.equals(stack.peek())) {
            stack.pop();
            return;
        }
        stack.removeFirstOccurrence(nodeId);
    }

    /**
     * 清理上下文。
     */
    public static void clear() {
        TRACE_ID.remove();
        NODE_STACK.get().clear();
        NODE_STACK.remove();
    }
}




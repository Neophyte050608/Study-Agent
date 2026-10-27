package io.github.imzmq.interview.agent.application.execution;

import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.domain.execution.DagStructure;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, request-scoped scheduler. Cancellation never frees a still-running worker. */
@Component
public final class FixedDagExecutor implements DagExecutionOperations, AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final Semaphore requests;
    private final int parallelism;

    public FixedDagExecutor() { this(4, 8, 4, 2); }

    public FixedDagExecutor(int threads, int queueCapacity, int maxRequests, int parallelism) {
        if (threads < 1 || queueCapacity < 1 || maxRequests < 1 || parallelism < 1) {
            throw new IllegalArgumentException("Execution limits must be positive");
        }
        this.parallelism = parallelism;
        this.requests = new Semaphore(maxRequests);
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), action -> {
                    Thread thread = new Thread(null, action, "learning-dag-" + sequence.incrementAndGet(), 0, false);
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public interface Checkpoint {
        void started(String id, Map<String, DagNodeResult> inputs);
        void completed(String id, DagNodeResult result);
    }
    private static final Checkpoint NOOP = new Checkpoint() {
        public void started(String id, Map<String, DagNodeResult> inputs) { }
        public void completed(String id, DagNodeResult result) { }
    };

    @Override
    public DagExecutionResult execute(List<DagNode> nodes, Duration timeout) {
        return executeCheckpointed(nodes, timeout, Map.of(), NOOP);
    }

    public DagExecutionResult executeCheckpointed(List<DagNode> nodes, Duration timeout,
                                                  Map<String, DagNodeResult> seed, Checkpoint checkpoint) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Deadline must be within 0..5 minutes");
        }
        List<DagNode> snapshot = List.copyOf(nodes);
        Map<String, DagNode> definitions = new LinkedHashMap<>();
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        snapshot.forEach(node -> { definitions.put(node.id(), node); edges.put(node.id(), node.dependencies()); });
        DagStructure graph = new DagStructure(snapshot.stream().map(DagNode::id).toList(), edges);
        Map<String, DagNodeResult> results = new LinkedHashMap<>();
        snapshot.forEach(node -> results.put(node.id(), new DagNodeResult(DagNodeStatus.PENDING, null, "PENDING")));
        seed.forEach((id, value) -> {
            if (!results.containsKey(id) || value.status() == DagNodeStatus.RUNNING || value.status() == DagNodeStatus.PENDING) {
                throw new IllegalArgumentException("Seed must contain known terminal nodes");
            }
            results.put(id, value);
        });
        seed.forEach((id, value) -> {
            if (!seed.keySet().containsAll(definitions.get(id).dependencies())) {
                throw new IllegalArgumentException("Seed dependencies must be committed");
            }
        });
        if (!requests.tryAcquire()) return stop(results, "OVERLOADED");
        Map<String, FutureTask<Completion>> inFlight = new LinkedHashMap<>();
        try {
            long end = System.nanoTime() + timeout.toNanos();
            String traceId = RAGTraceContext.getTraceId();
            String parentId = RAGTraceContext.getCurrentNodeId();
            Map<String, Integer> counts = graph.indegrees();
            seed.keySet().forEach(id -> graph.dependents(id).forEach(child -> counts.compute(child, (key, count) -> count - 1)));
            Deque<String> ready = new ArrayDeque<>();
            counts.forEach((id, count) -> { if (count == 0 && !seed.containsKey(id)) ready.add(id); });
            BlockingQueue<FutureTask<Completion>> completed = new LinkedBlockingQueue<>();
            int terminal = seed.size();
            while (terminal < snapshot.size()) {
                if (System.nanoTime() >= end) return stop(results, "TIMED_OUT");
                while (!ready.isEmpty() && inFlight.size() < parallelism) {
                    String id = ready.remove();
                    DagNode node = definitions.get(id);
                    Map<String, DagNodeResult> inputs = new LinkedHashMap<>();
                    node.dependencies().forEach(dep -> inputs.put(dep, results.get(dep)));
                    if (node.requireSuccessfulDependencies() && inputs.values().stream()
                            .anyMatch(result -> result.status() != DagNodeStatus.SUCCEEDED)) {
                        DagNodeResult skipped = new DagNodeResult(DagNodeStatus.SKIPPED, null, "DEPENDENCY_FAILED");
                        checkpoint.completed(id, skipped);
                        results.put(id, skipped);
                        terminal++; release(id, graph, counts, ready); continue;
                    }
                    FutureTask<Completion> task = new FutureTask<>(() -> {
                        checkpoint.started(id, Map.copyOf(inputs));
                        if(Thread.currentThread().isInterrupted()) throw new CancellationException("Execution cancelled before action");
                        Completion value = invoke(node, Map.copyOf(inputs), traceId, parentId);
                        checkpoint.completed(id, value.result());
                        return value;
                    }) {
                        @Override protected void done() { completed.add(this); }
                    };
                    workers.execute(task);
                    inFlight.put(id, task);
                    results.put(id, new DagNodeResult(DagNodeStatus.RUNNING, null, "RUNNING"));
                }
                if (inFlight.isEmpty()) continue;
                FutureTask<Completion> task = completed.poll(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (task == null) return stop(results, "TIMED_OUT");
                Completion completion = task.get();
                inFlight.remove(completion.id());
                results.put(completion.id(), completion.result());
                terminal++; release(completion.id(), graph, counts, ready);
            }
            boolean failed = results.values().stream().anyMatch(result -> result.status() != DagNodeStatus.SUCCEEDED);
            return new DagExecutionResult(failed ? "FAILED" : "COMPLETED", results);
        } catch (RejectedExecutionException e) {
            return stop(results, "OVERLOADED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return stop(results, "INTERRUPTED");
        } catch (ExecutionException | RuntimeException e) {
            return stop(results, "EXECUTION_FAILED");
        } finally {
            // Remove canceled queued tasks, but running tasks retain their physical thread until they exit.
            inFlight.values().forEach(task -> { task.cancel(true); workers.remove(task); });
            requests.release();
        }
    }

    private Completion invoke(DagNode node, Map<String, DagNodeResult> inputs, String traceId, String parentId) {
        RAGTraceContext.clear(); RAGTraceContext.setTraceId(traceId);
        if (parentId != null) RAGTraceContext.pushNode(parentId);
        try {
            Object output = node.action().apply(inputs);
            return new Completion(node.id(), new DagNodeResult(DagNodeStatus.SUCCEEDED, output, "OK"));
        } catch (RuntimeException e) {
            return new Completion(node.id(), new DagNodeResult(DagNodeStatus.FAILED, null, "NODE_FAILED"));
        } finally { RAGTraceContext.clear(); }
    }

    private void release(String id, DagStructure graph, Map<String, Integer> counts, Deque<String> ready) {
        for (String child : graph.dependents(id)) {
            if (counts.compute(child, (key, count) -> count - 1) == 0) ready.add(child);
        }
    }

    private DagExecutionResult stop(Map<String, DagNodeResult> results, String code) {
        results.replaceAll((id, result) -> switch (result.status()) {
            case PENDING -> new DagNodeResult(DagNodeStatus.SKIPPED, null, code);
            case RUNNING -> new DagNodeResult(DagNodeStatus.FAILED, null, code);
            default -> result;
        });
        return new DagExecutionResult(code, results);
    }

    @Override @PreDestroy
    public void close() { workers.shutdownNow(); }

    private record Completion(String id, DagNodeResult result) { }
}

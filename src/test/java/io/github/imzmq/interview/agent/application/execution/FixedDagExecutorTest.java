package io.github.imzmq.interview.agent.application.execution;

import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FixedDagExecutorTest {
    private static final Duration LIMIT = Duration.ofSeconds(3);

    private DagNode node(String id, Set<String> deps) {
        return new DagNode(id, deps, true, inputs -> id);
    }

    @Test
    void rejectsInvalidGraphsBeforeAnyWork() {
        AtomicInteger calls = new AtomicInteger();
        DagNode a = new DagNode("a", Set.of(), true, inputs -> calls.incrementAndGet());
        List<List<DagNode>> invalid = List.of(List.of(), List.of(a, a),
                List.of(a, node("b", Set.of("missing"))), List.of(node("a", Set.of("a"))),
                List.of(node("a", Set.of("b")), node("b", Set.of("a"))),
                java.util.stream.IntStream.range(0, 65).mapToObj(i -> node("n" + i, Set.of())).toList());
        try (var executor = new FixedDagExecutor()) {
            for (var graph : invalid) {
                assertThrows(IllegalArgumentException.class, () -> executor.execute(graph, LIMIT));
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    void overlapsIndependentNodesAndWaitsBeforeComposing() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        try (var executor = new FixedDagExecutor()) {
            List<DagNode> graph = List.of(
                    new DagNode("a", Set.of(), true, inputs -> { started.countDown(); await(release); return "A"; }),
                    new DagNode("b", Set.of(), true, inputs -> { started.countDown(); await(release); return "B"; }),
                    new DagNode("c", Set.of("a", "b"), true, inputs -> {
                        assertEquals(Set.of("a", "b"), inputs.keySet());
                        return inputs.get("a").output() + ":" + inputs.get("b").output();
                    }));
            Future<DagExecutionResult> run = caller.submit(() -> executor.execute(graph, LIMIT));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(run.isDone());
            release.countDown();
            var result = run.get(3, TimeUnit.SECONDS);
            assertEquals("COMPLETED", result.code());
            assertEquals("A:B", result.nodes().get("c").output());
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test
    void distinguishesRequiredSuccessFromWaitingForTermination() {
        try (var executor = new FixedDagExecutor()) {
            var result = executor.execute(List.of(
                    new DagNode("bad", Set.of(), true, inputs -> { throw new IllegalStateException("private detail"); }),
                    node("good", Set.of()), node("blocked", Set.of("bad")),
                    new DagNode("fallback", Set.of("bad", "good"), false, inputs -> inputs.get("bad").code())), LIMIT);
            assertEquals(DagNodeStatus.FAILED, result.nodes().get("bad").status());
            assertEquals(DagNodeStatus.SKIPPED, result.nodes().get("blocked").status());
            assertEquals("NODE_FAILED", result.nodes().get("fallback").output());
            assertEquals("FAILED", result.code());
        }
    }

    @Test
    void capsPerGraphConcurrency() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        try (var executor = new FixedDagExecutor()) {
            List<DagNode> graph = new ArrayList<>();
            for (int i = 0; i < 6; i++) graph.add(new DagNode("n" + i, Set.of(), true, inputs -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                started.countDown(); await(release); active.decrementAndGet(); return "ok";
            }));
            var run = caller.submit(() -> executor.execute(graph, LIMIT));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(2, active.get()); release.countDown();
            assertEquals("COMPLETED", run.get(3, TimeUnit.SECONDS).code());
            assertEquals(2, peak.get());
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test
    void rejectsExcessConcurrentRequestsWithoutRunningThem() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger unexpected = new AtomicInteger();
        var caller = Executors.newSingleThreadExecutor();
        try (var executor = new FixedDagExecutor(1, 1, 1, 1)) {
            var run = caller.submit(() -> executor.execute(List.of(new DagNode("a", Set.of(), true,
                    inputs -> { started.countDown(); await(release); return "ok"; })), LIMIT));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var rejected = executor.execute(List.of(new DagNode("b", Set.of(), true,
                    inputs -> unexpected.incrementAndGet())), LIMIT);
            assertEquals("OVERLOADED", rejected.code());
            assertEquals(0, unexpected.get()); release.countDown(); run.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test
    void timedOutWorkRetainsWorkerCapacityEvenWhenItIgnoresInterruption() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger extra = new AtomicInteger();
        var caller = Executors.newSingleThreadExecutor();
        try (var executor = new FixedDagExecutor(1, 1, 2, 1)) {
            var run = caller.submit(() -> executor.execute(List.of(new DagNode("slow", Set.of(), true, inputs -> {
                started.countDown();
                while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
                exited.countDown(); return "late";
            }), node("compose", Set.of("slow"))), Duration.ofMillis(500)));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var timedOut = run.get(2, TimeUnit.SECONDS);
            assertEquals("TIMED_OUT", timedOut.code());
            assertEquals(DagNodeStatus.SKIPPED, timedOut.nodes().get("compose").status());
            var queued = executor.execute(List.of(new DagNode("other", Set.of(), true,
                    inputs -> extra.incrementAndGet())), Duration.ofMillis(100));
            assertEquals("TIMED_OUT", queued.code()); assertEquals(0, extra.get());
            release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals("TIMED_OUT", timedOut.code());
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test
    void propagatesTraceAndParentAndClearsWorkerContextBetweenRequests() {
        try (var executor = new FixedDagExecutor(1, 2, 1, 1)) {
            RAGTraceContext.setTraceId("first"); RAGTraceContext.pushNode("parent");
            var first = executor.execute(List.of(new DagNode("a", Set.of(), true, inputs -> {
                assertEquals("first", RAGTraceContext.getTraceId());
                assertEquals("parent", RAGTraceContext.getCurrentNodeId());
                RAGTraceContext.pushNode("leaked"); return "ok";
            })), LIMIT);
            assertEquals("COMPLETED", first.code());
            assertEquals("parent", RAGTraceContext.getCurrentNodeId());
            RAGTraceContext.clear(); RAGTraceContext.setTraceId("second");
            var second = executor.execute(List.of(new DagNode("a", Set.of(), true, inputs -> {
                assertEquals("second", RAGTraceContext.getTraceId());
                assertNull(RAGTraceContext.getCurrentNodeId()); return "ok";
            })), LIMIT);
            assertEquals("COMPLETED", second.code());
        } finally { RAGTraceContext.clear(); }
    }

    @Test
    void rejectsSaturatedServiceQueueAndPreservesCompletedOutputsOnTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var callers = Executors.newFixedThreadPool(2);
        try (var executor = new FixedDagExecutor(1, 1, 3, 2)) {
            var first = callers.submit(() -> executor.execute(List.of(new DagNode("slow", Set.of(), true, inputs -> {
                started.countDown(); await(release); return "slow";
            })), LIMIT));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            // This graph submits two nodes while one worker is held: only one can fit the queue.
            var rejected = executor.execute(List.of(node("b", Set.of()), node("c", Set.of())), LIMIT);
            assertEquals("OVERLOADED", rejected.code());
            release.countDown(); assertEquals("COMPLETED", first.get(3, TimeUnit.SECONDS).code());
        } finally { release.countDown(); callers.shutdownNow(); }

        CountDownLatch slowRelease = new CountDownLatch(1);
        try (var executor = new FixedDagExecutor(1, 1, 1, 1)) {
            var result = executor.execute(List.of(node("done", Set.of()), new DagNode("slow", Set.of("done"), true,
                    inputs -> { await(slowRelease); return "late"; })), Duration.ofMillis(100));
            assertEquals("TIMED_OUT", result.code());
            assertEquals(DagNodeStatus.SUCCEEDED, result.nodes().get("done").status());
            assertEquals("done", result.nodes().get("done").output());
        } finally { slowRelease.countDown(); }
    }

    @Test
    void validatesDeadlineAndReturnsControlledFailureWhenCallerIsInterrupted() throws Exception {
        try (var executor = new FixedDagExecutor()) {
            for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(6))) {
                assertThrows(IllegalArgumentException.class, () -> executor.execute(List.of(node("a", Set.of())), timeout));
            }
            assertThrows(IllegalArgumentException.class, () -> executor.execute(List.of(node("a", Set.of())), null));
            assertEquals("COMPLETED", executor.execute(java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> node("n" + i, Set.of())).toList(), LIMIT).code());
        }
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<DagExecutionResult>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = new FixedDagExecutor()) {
            Thread caller = new Thread(() -> {
                result.set(executor.execute(List.of(new DagNode("slow", Set.of(), true, inputs -> {
                    started.countDown(); await(release); return "late";
                }), node("compose", Set.of("slow"))), LIMIT));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                caller.interrupt(); caller.join(2000);
                assertFalse(caller.isAlive()); assertTrue(interrupted.get());
                assertEquals("INTERRUPTED", result.get().code());
                assertEquals(DagNodeStatus.SKIPPED, result.get().nodes().get("compose").status());
            } finally { caller.interrupt(); release.countDown(); caller.join(2000); }
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier expired"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}

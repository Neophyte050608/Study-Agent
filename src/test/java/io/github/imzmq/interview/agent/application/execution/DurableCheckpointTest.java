package io.github.imzmq.interview.agent.application.execution;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class DurableCheckpointTest {
    @Test void reusesCommittedSeedAndCommitsBeforeReleasingDependent() {
        AtomicInteger skipped = new AtomicInteger(), committed = new AtomicInteger();
        try (var executor = new FixedDagExecutor()) {
            var result = executor.executeCheckpointed(List.of(
                    new DagNode("a", Set.of(), true, inputs -> skipped.incrementAndGet()),
                    new DagNode("b", Set.of("a"), true, inputs -> { assertEquals("saved", inputs.get("a").output()); return "B"; }),
                    new DagNode("c", Set.of("b"), true, inputs -> { assertEquals(1, committed.get()); return "C"; })),
                    Duration.ofSeconds(2), Map.of("a", new DagNodeResult(DagNodeStatus.SUCCEEDED, "saved", "OK")),
                    new FixedDagExecutor.Checkpoint() {
                        public void started(String id, Map<String, DagNodeResult> inputs) { }
                        public void completed(String id, DagNodeResult value) { committed.incrementAndGet(); }
                    });
            assertEquals("COMPLETED", result.code()); assertEquals(0, skipped.get()); assertEquals(2, committed.get());
        }
    }
    @Test void persistenceFailureStopsDependentAndDoesNotPretendNodeSucceeded() {
        AtomicInteger dependent = new AtomicInteger();
        try (var executor = new FixedDagExecutor()) {
            var result = executor.executeCheckpointed(List.of(new DagNode("a", Set.of(), true, i -> "A"),
                    new DagNode("b", Set.of("a"), true, i -> dependent.incrementAndGet())), Duration.ofSeconds(2), Map.of(),
                    new FixedDagExecutor.Checkpoint() {
                        public void started(String id, Map<String, DagNodeResult> inputs) { }
                        public void completed(String id, DagNodeResult value) { throw new IllegalStateException("database down"); }
                    });
            assertEquals("EXECUTION_FAILED", result.code()); assertEquals(0, dependent.get());
        }
    }
    @Test void rejectsSuccessfulSeedWithoutCommittedDependencies() {
        try(var executor=new FixedDagExecutor()) {
            assertThrows(IllegalArgumentException.class,()->executor.executeCheckpointed(List.of(
                    new DagNode("a",Set.of(),true,i->"A"),
                    new DagNode("b",Set.of("a"),true,i->"B")),Duration.ofSeconds(1),
                    Map.of("b",new DagNodeResult(DagNodeStatus.SUCCEEDED,"saved","OK")),
                    new FixedDagExecutor.Checkpoint() {
                        public void started(String id,Map<String,DagNodeResult> inputs) { }
                        public void completed(String id,DagNodeResult value) { }
                    }));
        }
    }

    @Test void synchronousAndCheckpointedGraphsShareFourPhysicalWorkers() throws Exception {
        var active=new AtomicInteger();var peak=new AtomicInteger();
        var entered=new java.util.concurrent.CountDownLatch(4);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=new FixedDagExecutor();var callers=java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var graph=List.of(new DagNode("a",Set.of(),true,i->{
                peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
                try {release.await();return "A";}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                finally {active.decrementAndGet();}
            }),new DagNode("b",Set.of(),true,i->{
                peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
                try {release.await();return "B";}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                finally {active.decrementAndGet();}
            }));
            var one=callers.submit(()->executor.execute(graph,Duration.ofSeconds(3)));
            var two=callers.submit(()->executor.execute(graph,Duration.ofSeconds(3)));
            var durable=callers.submit(()->executor.executeCheckpointed(graph,Duration.ofSeconds(3),Map.of(),new FixedDagExecutor.Checkpoint() {
                public void started(String id,Map<String,DagNodeResult> inputs) { }
                public void completed(String id,DagNodeResult result) { }
            }));
            try {assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(4,active.get());}
            finally {release.countDown();}
            assertEquals("COMPLETED",one.get().code());assertEquals("COMPLETED",two.get().code());assertEquals("COMPLETED",durable.get().code());
            assertEquals(4,peak.get());
        } finally {release.countDown();}
    }

}

package io.github.imzmq.interview.agent.application.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableWorkflow;
import io.github.imzmq.interview.agent.infrastructure.persistence.execution.ExecutionTaskDO;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableExecutionWorkerTest {
    @Test void cancelledCoordinatorKeepsCapacityUntilItsActualCallReturns() throws Exception {
        var tx = mock(ExecutionTransactions.class);
        var registry = mock(DurableWorkflowRegistry.class);
        var service = mock(DurableExecutionService.class);
        var workflow = mock(DurableWorkflow.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(tx.claim(anyString(), anyInt())).thenAnswer(i -> {
            var task = new ExecutionTaskDO(); task.setTaskId(UUID.randomUUID().toString());
            task.setWorkflowType("TEST"); task.setWorkflowVersion(1); task.setDefinitionJson("[]");
            return task;
        });
        when(registry.require("TEST", 1)).thenReturn(workflow);
        when(workflow.nodes(any(), any())).thenReturn(List.of());
        when(service.definition(any())).thenReturn("[]");
        when(tx.snapshot(any())).thenAnswer(i -> {
            entered.countDown();
            while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return List.of();
        });
        try (var dag = new FixedDagExecutor()) {
            var worker = new DurableExecutionWorker(tx, service, registry, new ExecutionJson(new ObjectMapper()), dag, new DurableExecutionProperties());
            try {
                invoke(worker, "poll"); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                invoke(worker, "heartbeat"); invoke(worker, "poll");
                verify(tx, times(2)).claim(anyString(), anyInt());
            } finally { release.countDown(); worker.close(); }
        }
    }
    private void invoke(Object value, String method) throws Exception {
        var m = value.getClass().getDeclaredMethod(method); m.setAccessible(true); m.invoke(value);
    }
}

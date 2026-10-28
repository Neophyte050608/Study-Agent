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
            task.setOwnerUserId("owner"); task.setWorkflowType("TEST"); task.setWorkflowVersion(1);
            task.setInputJson("{}"); task.setDefinitionJson("[]");
            return task;
        });
        when(registry.require("TEST", 1)).thenReturn(workflow);
        when(workflow.restore("owner", "{}", "[]")).thenReturn(List.of(
                new DagNode("source", Set.of(), true, ignored -> "ok")));
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
    @Test void workerRestoresPersistedDefinitionWithoutPreparing() {
        var tx = mock(ExecutionTransactions.class);
        var registry = mock(DurableWorkflowRegistry.class);
        var service = mock(DurableExecutionService.class);
        var workflow = mock(DurableWorkflow.class);
        var task = new ExecutionTaskDO();
        task.setTaskId("task"); task.setOwnerUserId("owner"); task.setWorkflowType("TEST");
        task.setWorkflowVersion(2); task.setInputJson("{}");
        task.setDefinitionJson("{\"schemaVersion\":\"2\"}");
        task.setLeaseOwner("worker"); task.setLeaseEpoch(1L);
        var node = new DagNode("source", Set.of(), true, ignored -> "ok");
        var persisted = new io.github.imzmq.interview.agent.infrastructure.persistence.execution.ExecutionNodeDO();
        persisted.setNodeId("source"); persisted.setStatus("PENDING");
        when(registry.require("TEST", 2)).thenReturn(workflow);
        when(workflow.prepare(anyString(), anyString()))
                .thenThrow(new AssertionError("worker must not prepare"));
        when(workflow.restore("owner", "{}", "{\"schemaVersion\":\"2\"}"))
                .thenReturn(List.of(node));
        when(service.validateNodes(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tx.snapshot(task)).thenReturn(List.of(persisted));
        when(tx.remaining(task)).thenReturn(TimeUnit.SECONDS.toMicros(1));
        when(workflow.encodeOutput("source", "ok")).thenReturn("\"ok\"");
        when(workflow.outcome(any())).thenReturn(new DurableWorkflow.WorkflowOutcome("COMPLETED", "{}"));

        try (var dag = new FixedDagExecutor()) {
            var worker = new DurableExecutionWorker(tx, service, registry,
                    new ExecutionJson(new ObjectMapper()), dag, new DurableExecutionProperties());
            try { worker.run(task); } finally { worker.close(); }
        }

        verify(workflow, never()).prepare(anyString(), anyString());
        verify(workflow).restore("owner", "{}", "{\"schemaVersion\":\"2\"}");
        verify(tx).finish(task, "COMPLETED", "{}", null);
    }

    @Test void definitionNodeMismatchFailsWithoutExecutingAnyAction() {
        var tx = mock(ExecutionTransactions.class);
        var registry = mock(DurableWorkflowRegistry.class);
        var service = mock(DurableExecutionService.class);
        var workflow = mock(DurableWorkflow.class);
        var task = new ExecutionTaskDO();
        task.setTaskId("task"); task.setOwnerUserId("owner"); task.setWorkflowType("TEST");
        task.setWorkflowVersion(2); task.setInputJson("{}"); task.setDefinitionJson("definition");
        task.setLeaseOwner("worker"); task.setLeaseEpoch(1L);
        var action = mock(java.util.function.Function.class);
        var restored = new DagNode("restored", Set.of(), true, action);
        var saved = new io.github.imzmq.interview.agent.infrastructure.persistence.execution.ExecutionNodeDO();
        saved.setNodeId("persisted"); saved.setStatus("PENDING");
        when(registry.require("TEST", 2)).thenReturn(workflow);
        when(workflow.restore("owner", "{}", "definition")).thenReturn(List.of(restored));
        when(service.validateNodes(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tx.snapshot(task)).thenReturn(List.of(saved));

        try (var dag = new FixedDagExecutor()) {
            var worker = new DurableExecutionWorker(tx, service, registry,
                    new ExecutionJson(new ObjectMapper()), dag, new DurableExecutionProperties());
            try { worker.run(task); } finally { worker.close(); }
        }

        verify(action, never()).apply(any());
        verify(workflow, never()).prepare(anyString(), anyString());
        verify(tx).finish(task, "FAILED", null, "EXECUTION_FAILED");
        verify(tx, never()).start(any(), anyString(), anyString());
        verify(tx, never()).complete(any(), anyString(), any(), any());
    }

    private void invoke(Object value, String method) throws Exception {
        var m = value.getClass().getDeclaredMethod(method); m.setAccessible(true); m.invoke(value);
    }
}

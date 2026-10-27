package io.github.imzmq.interview.agent.application.execution;
import io.github.imzmq.interview.agent.infrastructure.persistence.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.durable.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DurableExecutionServiceTest {
    @Test void lostLeasePreventsNodeOrTaskMutation() {
        var mapper = mock(ExecutionMapper.class);
        var tx = new ExecutionTransactions(mapper, new ExecutionJson(new ObjectMapper()));
        var task = new ExecutionTaskDO(); task.setTaskId("t"); task.setLeaseOwner("w"); task.setLeaseEpoch(2L);
        assertThrows(IllegalStateException.class, () -> tx.start(task,"a","{}"));
        assertThrows(IllegalStateException.class, () -> tx.complete(task,"a",new DagNodeResult(DagNodeStatus.SUCCEEDED,"ok","OK"),"\"ok\""));
        assertThrows(IllegalStateException.class, () -> tx.finish(task,"COMPLETED","{}",null));
        verify(mapper,times(3)).fenced("t","w",2);
        verifyNoMoreInteractions(mapper);
    }
    @Test void retryChecksOwnerRevisionAndInvalidatesDescendantsAtomically() {
        var mapper = mock(ExecutionMapper.class);
        var tx = new ExecutionTransactions(mapper, new ExecutionJson(new ObjectMapper()));
        var task = new ExecutionTaskDO(); task.setTaskId("t"); task.setRevision(1); task.setStatus("DEGRADED");
        when(mapper.owned("owner","t")).thenReturn(task);
        when(mapper.nodes("t")).thenReturn(List.of(node("profile","SUCCEEDED"),node("evidence","FAILED"),node("compose","SUCCEEDED")));
        var deps = Map.of("profile",Set.<String>of(),"evidence",Set.<String>of(),"compose",Set.of("profile","evidence"));
        assertThrows(IllegalStateException.class, () -> tx.retry("owner","t",0,Set.of("evidence"),deps));
        tx.retry("owner","t",1,Set.of("evidence"),deps);
        verify(mapper).resetNode("t","evidence"); verify(mapper).resetNode("t","compose");
        verify(mapper,never()).resetNode("t","profile"); verify(mapper).retry("t");
        assertThrows(IllegalArgumentException.class, () -> tx.query("stranger","t"));
    }
    private ExecutionNodeDO node(String id,String state) {
        var node=new ExecutionNodeDO(); node.setNodeId(id);node.setStatus(state);node.setAttemptCount(1);return node;
    }
}

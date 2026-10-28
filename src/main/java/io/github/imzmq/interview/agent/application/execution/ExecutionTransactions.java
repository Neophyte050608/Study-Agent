package io.github.imzmq.interview.agent.application.execution;

import io.github.imzmq.interview.agent.infrastructure.persistence.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableTaskView;
import io.github.imzmq.interview.agent.domain.execution.DurableRetrySelection;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Short DB transactions only. Never execute a business node or model call while holding the lock. */
@Service
public class ExecutionTransactions {
    private static final Set<String> TERMINAL = Set.of("COMPLETED","DEGRADED","FAILED","TIMED_OUT");
    private final ExecutionMapper mapper;
    private final ExecutionJson json;
    public ExecutionTransactions(ExecutionMapper mapper, ExecutionJson json) { this.mapper=mapper; this.json=json; }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public DurableTaskView submit(ExecutionTaskDO task, List<String> nodes) {
        boolean created;
        try { mapper.insertTask(task); created=true; }
        catch(DuplicateKeyException duplicate) { created=false; }
        ExecutionTaskDO existing=mapper.byKey(task.getOwnerUserId(),task.getWorkflowType(),task.getIdempotencyKey());
        if(existing==null) throw new IllegalStateException("EXECUTION_SUBMISSION_FAILED");
        if(!existing.getInputHash().equals(task.getInputHash()) || !existing.getWorkflowVersion().equals(task.getWorkflowVersion())) {
            throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
        }
        if(created) for(String id:nodes) mapper.insertNode(existing.getTaskId(),id);
        return view(existing);
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public DurableTaskView query(String owner,String taskId) { return view(owned(owner,taskId)); }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ExecutionTaskDO identity(String owner,String id) { return owned(owner,id); }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public void delete(String owner,String taskId) {
        var task=owned(owner,taskId);
        if(!TERMINAL.contains(task.getStatus())) throw new IllegalStateException("EXECUTION_NOT_TERMINAL");
        mapper.delete(taskId);
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ExecutionTaskDO claim(String worker,int leaseSeconds) {
        var task=mapper.claimable();
        if(task==null) return null;
        mapper.lease(task.getTaskId(),worker,leaseSeconds);
        mapper.abandon(task.getTaskId());
        mapper.recover(task.getTaskId(),3);
        return mapper.task(task.getTaskId());
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public boolean heartbeat(ExecutionTaskDO task,int seconds) {
        if(mapper.fenced(task.getTaskId(),task.getLeaseOwner(),task.getLeaseEpoch())==null) return false;
        return mapper.heartbeat(task.getTaskId(),task.getLeaseOwner(),task.getLeaseEpoch(),seconds)==1;
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public long remaining(ExecutionTaskDO task) {
        fence(task);
        return mapper.remainingMicros(task.getTaskId());
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public List<ExecutionNodeDO> snapshot(ExecutionTaskDO task) { fence(task); return mapper.nodes(task.getTaskId()); }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public void start(ExecutionTaskDO task,String nodeId,String input) {
        var current=fence(task);
        if(mapper.remainingMicros(task.getTaskId())<=0) throw new IllegalStateException("EXECUTION_DEADLINE");
        var node=node(task,nodeId);
        String attempt=UUID.randomUUID().toString();
        if(!"PENDING".equals(node.getStatus()) || node.getAttemptCount()>=3) throw new IllegalStateException("ATTEMPT_BUDGET_EXHAUSTED");
        mapper.startAttempt(attempt,task.getTaskId(),nodeId,node.getAttemptCount()+1,current.getRevision(),task.getLeaseEpoch(),json.check(input,65536));
        if(mapper.startNode(task.getTaskId(),nodeId,attempt,3)!=1) throw new IllegalStateException("NODE_STATE_CONFLICT");
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public void complete(ExecutionTaskDO task,String nodeId,DagNodeResult result,String output) {
        fence(task);
        if(mapper.remainingMicros(task.getTaskId())<=0) throw new IllegalStateException("EXECUTION_DEADLINE");
        var node=node(task,nodeId);
        String status=result.status().name();
        if(!Set.of("SUCCEEDED","FAILED","SKIPPED").contains(status)) throw new IllegalArgumentException("INVALID_TERMINAL_NODE");
        if(("SUCCEEDED".equals(status)) != (output!=null)) throw new IllegalArgumentException("INVALID_NODE_OUTPUT_STATE");
        String checked=output==null?null:json.check(output,65536);
        if("RUNNING".equals(node.getStatus())) {
            if(mapper.finishAttempt(node.getCurrentAttemptId(),status,checked,result.code())!=1) throw new IllegalStateException("ATTEMPT_STATE_CONFLICT");
        } else if(!("PENDING".equals(node.getStatus()) && "SKIPPED".equals(status))) throw new IllegalStateException("NODE_STATE_CONFLICT");
        if(mapper.finishNode(task.getTaskId(),nodeId,status,checked,result.code())!=1) throw new IllegalStateException("NODE_STATE_CONFLICT");
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public void finish(ExecutionTaskDO task,String status,String result,String code) {
        fence(task);
        if(!TERMINAL.contains(status)) throw new IllegalArgumentException("INVALID_TASK_TERMINAL");
        if(mapper.remainingMicros(task.getTaskId())<=0) {
            status="TIMED_OUT"; result=null; code="TIMED_OUT";
        }
        var nodes=mapper.nodes(task.getTaskId());
        if(Set.of("COMPLETED","DEGRADED").contains(status) && (result==null || nodes.stream().anyMatch(n->Set.of("RUNNING","PENDING").contains(n.getStatus())))) {
            throw new IllegalStateException("EXECUTION_INCOMPLETE");
        }
        for(var node:nodes) {
            if("RUNNING".equals(node.getStatus())) {
                mapper.finishAttempt(node.getCurrentAttemptId(),"FAILED",null,code==null?"EXECUTION_FAILED":code);
                mapper.finishNode(task.getTaskId(),node.getNodeId(),"FAILED",null,code==null?"EXECUTION_FAILED":code);
            } else if("PENDING".equals(node.getStatus())) {
                mapper.finishNode(task.getTaskId(),node.getNodeId(),"FAILED",null,code==null?"EXECUTION_FAILED":code);
            }
        }
        mapper.finishTask(task.getTaskId(),status,result==null?null:json.check(result,131072),code);
    }
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public void retry(String owner,String taskId,int revision,Set<String> selected,Map<String,Set<String>> deps) {
        var task=owned(owner,taskId);
        if(task.getRevision()!=revision) throw new IllegalStateException("REVISION_CONFLICT");
        if(!Set.of("FAILED","TIMED_OUT","DEGRADED").contains(task.getStatus())) throw new IllegalStateException("EXECUTION_NOT_RETRYABLE");
        Map<String,String> states=new LinkedHashMap<>(); Map<String,Integer> counts=new LinkedHashMap<>();
        mapper.nodes(taskId).forEach(n->{ states.put(n.getNodeId(),n.getStatus()); counts.put(n.getNodeId(),n.getAttemptCount()); });
        if(!states.keySet().equals(deps.keySet())) throw new IllegalStateException("WORKFLOW_DEFINITION_MISMATCH");
        var reset=DurableRetrySelection.select(deps,states,counts,selected,3);
        reset.forEach(id->mapper.resetNode(taskId,id)); mapper.retry(taskId);
    }
    private ExecutionTaskDO owned(String owner,String id) {
        var task=mapper.owned(owner,id); if(task==null) throw new IllegalArgumentException("EXECUTION_NOT_FOUND");return task;
    }
    private ExecutionTaskDO fence(ExecutionTaskDO task) {
        var current=mapper.fenced(task.getTaskId(),task.getLeaseOwner(),task.getLeaseEpoch());
        if(current==null || mapper.liveLease(task.getTaskId(),task.getLeaseOwner(),task.getLeaseEpoch())!=1) throw new IllegalStateException("EXECUTION_LEASE_LOST"); return current;
    }
    private ExecutionNodeDO node(ExecutionTaskDO task,String id) {
        return mapper.nodes(task.getTaskId()).stream().filter(n->n.getNodeId().equals(id)).findFirst()
                .orElseThrow(()->new IllegalStateException("WORKFLOW_NODE_MISSING"));
    }
    private DurableTaskView view(ExecutionTaskDO task) {
        Map<String,DurableTaskView.NodeView> nodes=new LinkedHashMap<>();
        mapper.nodes(task.getTaskId()).forEach(n->nodes.put(n.getNodeId(),new DurableTaskView.NodeView(n.getStatus(),n.getAttemptCount(),n.getErrorCode())));
        return new DurableTaskView(task.getTaskId(),task.getStatus(),task.getRevision(),task.getFinalResultJson(),task.getErrorCode(),nodes,task.getWorkflowType(),task.getWorkflowVersion());
    }
}

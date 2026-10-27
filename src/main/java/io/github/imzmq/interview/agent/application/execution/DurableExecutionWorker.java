package io.github.imzmq.interview.agent.application.execution;

import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.infrastructure.persistence.execution.ExecutionTaskDO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Only two coordinators; actual nodes share the existing FixedDagExecutor physical workers. */
@Component
@ConditionalOnProperty(name="app.agent.execution.enabled",havingValue="true")
public final class DurableExecutionWorker {
    private final ExecutionTransactions tx;
    private final DurableExecutionService service;
    private final DurableWorkflowRegistry registry;
    private final ExecutionJson json;
    private final FixedDagExecutor executor;
    private final String worker=UUID.randomUUID().toString();
    private final ScheduledExecutorService control=Executors.newScheduledThreadPool(2,r->thread(r,"durable-control"));
    private final ExecutorService coordinators;
    private final DurableExecutionProperties properties;
    private final Semaphore capacity;
    private final Map<ExecutionTaskDO,Future<?>> active=new ConcurrentHashMap<>();
    public DurableExecutionWorker(ExecutionTransactions tx,DurableExecutionService service,DurableWorkflowRegistry registry,
                                  ExecutionJson json,FixedDagExecutor executor,DurableExecutionProperties properties) {
        this.tx=tx;this.service=service;this.registry=registry;this.json=json;this.executor=executor;
        properties.validate();this.properties=properties;
        int slots=properties.getCoordinators();capacity=new Semaphore(slots);
        coordinators=new ThreadPoolExecutor(slots,slots,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(slots),r->thread(r,"durable-coordinator"),new ThreadPoolExecutor.AbortPolicy());
    }
    @PostConstruct public void start() {
        control.scheduleWithFixedDelay(this::poll,0,properties.getPollSeconds(),TimeUnit.SECONDS);
        control.scheduleWithFixedDelay(this::heartbeat,properties.getHeartbeatSeconds(),properties.getHeartbeatSeconds(),TimeUnit.SECONDS);
    }
    private void poll() {
        while(capacity.tryAcquire()) {
            boolean handedOff=false;
            ExecutionTaskDO claimed=null;
            try {
                var task=tx.claim(worker,properties.getLeaseSeconds());
                if(task==null) return;
                claimed=task;
                FutureTask<Void> run=new FutureTask<>(()->{ run(task);return null; }) {
                    public void run() { try { super.run(); } finally { active.remove(task);capacity.release(); } }
                };
                active.put(task,run);coordinators.execute(run);handedOff=true;
            } catch(RuntimeException ignored) {
                if(claimed!=null) {var pending=active.remove(claimed);if(pending!=null) pending.cancel(true);}
                return;
            }
            finally { if(!handedOff) capacity.release(); }
        }
    }
    private void heartbeat() {
        active.forEach((task,future)->{
            try { if(!future.isCancelled() && !tx.heartbeat(task,properties.getLeaseSeconds())) future.cancel(true); }
            catch(RuntimeException e) { future.cancel(true); }
        });
    }
    void run(ExecutionTaskDO task) {
        Future<?> lifetime=active.get(task);
        try {
            requireLive(lifetime);
            var workflow=registry.require(task.getWorkflowType(),task.getWorkflowVersion());
            var nodes=workflow.nodes(task.getOwnerUserId(),task.getInputJson());
            if(!service.definition(nodes).equals(json.check(task.getDefinitionJson(),65536))) throw new IllegalStateException("WORKFLOW_DEFINITION_MISMATCH");
            Map<String,DagNodeResult> seed=new LinkedHashMap<>();
            var saved=tx.snapshot(task);
            if(!saved.stream().map(n->n.getNodeId()).collect(java.util.stream.Collectors.toSet())
                    .equals(nodes.stream().map(DagNode::id).collect(java.util.stream.Collectors.toSet()))) throw new IllegalStateException("WORKFLOW_NODE_MISSING");
            for(var node:saved) {
                var state=DagNodeStatus.valueOf(node.getStatus());
                if(state==DagNodeStatus.RUNNING) throw new IllegalStateException("UNRECOVERED_NODE");
                if(state!=DagNodeStatus.PENDING) seed.put(node.getNodeId(),new DagNodeResult(state,
                        state==DagNodeStatus.SUCCEEDED?workflow.decodeOutput(node.getNodeId(),json.check(node.getOutputJson(),65536)):null,node.getErrorCode()));
            }
            long micros=tx.remaining(task);
            if(micros<=0) {tx.finish(task,"TIMED_OUT",null,"TIMED_OUT");return;}
            var result=executor.executeCheckpointed(nodes,Duration.ofNanos(micros*1000),seed,new FixedDagExecutor.Checkpoint() {
                public void started(String id,Map<String,DagNodeResult> inputs) {
                    requireLive(lifetime);
                    Map<String,Object> dependencies=new TreeMap<>();
                    inputs.forEach((dep,value)->dependencies.put(dep,new Frozen(value.status().name(),value.code(),
                            value.status()==DagNodeStatus.SUCCEEDED?workflow.encodeOutput(dep,value.output()):null)));
                    tx.start(task,id,json.write(Map.of("taskInput",json.check(task.getInputJson(),16384),"dependencies",dependencies),65536));
                }
                public void completed(String id,DagNodeResult value) {
                    requireLive(lifetime);
                    String output=null;
                    if(value.status()==DagNodeStatus.SUCCEEDED) {
                        try {output=json.check(workflow.encodeOutput(id,value.output()),65536);}
                        catch(RuntimeException e) {tx.complete(task,id,new DagNodeResult(DagNodeStatus.FAILED,null,"OUTPUT_INVALID"),null);throw e;}
                    }
                    tx.complete(task,id,value,output);
                }
            });
            requireLive(lifetime);
            if(Set.of("COMPLETED","FAILED").contains(result.code())) {
                var outcome=workflow.outcome(result);tx.finish(task,outcome.status(),outcome.resultJson(),null);
            } else tx.finish(task,"TIMED_OUT".equals(result.code())?"TIMED_OUT":"FAILED",null,result.code());
        } catch(RuntimeException e) {
            try {requireLive(lifetime);tx.finish(task,"FAILED",null,"EXECUTION_FAILED");} catch(RuntimeException ignored) { /* lease recovery owns future progress */ }
        }
    }
    @PreDestroy public void close() {control.shutdownNow();active.values().forEach(f->f.cancel(true));coordinators.shutdownNow().forEach(Runnable::run);}
    private void requireLive(Future<?> future) {
        if(Thread.currentThread().isInterrupted() || (future!=null && future.isCancelled())) throw new IllegalStateException("EXECUTION_LEASE_LOST");
    }
    private static Thread thread(Runnable action,String name) {var t=new Thread(null,action,name,0,false);t.setDaemon(true);return t;}
    private record Frozen(String status,String code,String outputJson) { }
}

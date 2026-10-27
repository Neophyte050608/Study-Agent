package io.github.imzmq.interview.agent.application.execution;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.durable.*;
import io.github.imzmq.interview.agent.infrastructure.persistence.execution.*;
import io.github.imzmq.interview.agent.domain.execution.DagStructure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public final class DurableExecutionService implements DurableExecutionOperations {
    private final ExecutionTransactions tx;
    private final DurableWorkflowRegistry registry;
    private final ExecutionJson json;
    private final boolean enabled;
    public DurableExecutionService(ExecutionTransactions tx,DurableWorkflowRegistry registry,ExecutionJson json,
                                   @Value("${app.agent.execution.enabled:false}") boolean enabled) {
        this.tx=tx;this.registry=registry;this.json=json;this.enabled=enabled;
    }
    public DurableTaskView submit(String owner,String type,int version,String key,String inputJson) {
        if(!enabled) throw new IllegalStateException("EXECUTION_DISABLED");
        require(owner,200); require(type,80); require(key,120);
        String input=json.check(inputJson,16384);
        var workflow=registry.require(type,version);
        var nodes=workflow.nodes(owner,input);
        String definition=definition(nodes);
        var task=new ExecutionTaskDO();task.setTaskId(UUID.randomUUID().toString());task.setOwnerUserId(owner);
        task.setWorkflowType(type);task.setWorkflowVersion(version);task.setIdempotencyKey(key);task.setInputJson(input);
        task.setDefinitionJson(definition);task.setInputHash(hash(input));
        return tx.submit(task,nodes.stream().map(DagNode::id).toList());
    }
    public DurableTaskView query(String owner,String id) { require(owner,200);return tx.query(owner,id); }
    public DurableTaskView retry(String owner,String id,int revision,Set<String> selected) {
        if(!enabled) throw new IllegalStateException("EXECUTION_DISABLED");
        require(owner,200);
        // Retrieve workflow identity owner-scoped, never disclose task existence by probing globally.
        var identity=tx.identity(owner,id);
        var workflow=registry.require(identity.getWorkflowType(),identity.getWorkflowVersion());
        var nodes=workflow.nodes(owner,identity.getInputJson());
        if(!definition(nodes).equals(json.check(identity.getDefinitionJson(),65536))) throw new IllegalStateException("WORKFLOW_DEFINITION_MISMATCH");
        Map<String,Set<String>> deps=new LinkedHashMap<>();nodes.forEach(n->deps.put(n.id(),n.dependencies()));
        tx.retry(owner,id,revision,Set.copyOf(selected),deps);return tx.query(owner,id);
    }
    public void delete(String owner,String id) { require(owner,200);tx.delete(owner,id); }
    String definition(List<DagNode> nodes) {
        Map<String,Set<String>> deps=new LinkedHashMap<>();nodes.forEach(n->deps.put(n.id(),n.dependencies()));
        nodes.forEach(n -> require(n.id(),120));
        new DagStructure(nodes.stream().map(DagNode::id).toList(),deps);
        return json.write(nodes.stream().sorted(Comparator.comparing(DagNode::id)).map(n->new NodeDefinition(n.id(),n.dependencies().stream().sorted().toList(),n.requireSuccessfulDependencies())).toList(),65536);
    }
    private record NodeDefinition(String id,List<String> dependencies,boolean requireSuccess) { }
    private void require(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("INVALID_EXECUTION_ARGUMENT");
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}

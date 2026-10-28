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
        var prepared=workflow.prepare(owner,input);
        String definition=json.check(prepared.definitionJson(),65536);
        var nodes=validateNodes(prepared.nodes());
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
        String definition=json.check(identity.getDefinitionJson(),65536);
        var nodes=validateNodes(workflow.restore(owner,identity.getInputJson(),definition));
        Map<String,Set<String>> deps=dependencies(nodes);
        tx.retry(owner,id,revision,Set.copyOf(selected),deps);return tx.query(owner,id);
    }
    public void delete(String owner,String id) { require(owner,200);tx.delete(owner,id); }
    List<DagNode> validateNodes(List<DagNode> nodes) {
        if(nodes==null) throw new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");
        List<DagNode> validated=List.copyOf(nodes);
        validated.forEach(node -> {if(node==null) throw new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");require(node.id(),120);});
        new DagStructure(validated.stream().map(DagNode::id).toList(),dependencies(validated));
        return validated;
    }
    private Map<String,Set<String>> dependencies(List<DagNode> nodes) {
        Map<String,Set<String>> dependencies=new LinkedHashMap<>();
        nodes.forEach(node->dependencies.put(node.id(),node.dependencies()));
        return dependencies;
    }
    private void require(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("INVALID_EXECUTION_ARGUMENT");
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}

package io.github.imzmq.interview.agent.application.execution;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableWorkflow;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public final class DurableWorkflowRegistry {
    private final Map<String,DurableWorkflow> workflows=new HashMap<>();
    public DurableWorkflowRegistry(List<DurableWorkflow> list) {
        for(var workflow:list) if(workflows.put(workflow.type()+"/"+workflow.version(),workflow)!=null) {
            throw new IllegalArgumentException("DUPLICATE_WORKFLOW_VERSION");
        }
    }
    public DurableWorkflow require(String type,int version) {
        var workflow=workflows.get(type+"/"+version);
        if(workflow==null) throw new IllegalArgumentException("WORKFLOW_VERSION_UNAVAILABLE");return workflow;
    }
}

package io.github.imzmq.interview.learning.application.planning;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableWorkflow;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class LearningPlanWorkflow implements DurableWorkflow {
    private final LearningPlanPreviewService preview;
    private final ObjectMapper mapper;
    public LearningPlanWorkflow(LearningPlanPreviewService preview,ObjectMapper mapper) { this.preview=preview;this.mapper=mapper; }
    public String type() { return "LEARNING_PLAN_PREVIEW"; }
    public int version() { return 1; }
    public List<DagNode> nodes(String owner,String inputJson) {
        try {
            var tree=mapper.readTree(inputJson);
            if(!tree.isObject() || tree.size()!=2 || !tree.path("topic").isTextual() || !tree.path("request").isTextual()) throw new IllegalArgumentException();
            return preview.nodes(owner,tree.get("topic").textValue(),tree.get("request").textValue());
        } catch(Exception e) { throw new IllegalArgumentException("INVALID_PREVIEW_INPUT"); }
    }
    public String encodeOutput(String nodeId,Object output) {
        try {
            String json=mapper.writeValueAsString(output);
            decodeOutput(nodeId,json);return json;
        } catch(Exception e) { throw new IllegalArgumentException("INVALID_PREVIEW_OUTPUT"); }
    }
    public Object decodeOutput(String nodeId,String outputJson) {
        try {
            var tree=mapper.readTree(outputJson);
            if(Set.of("profile","compose").contains(nodeId)) {
                if(!tree.isTextual()) throw new IllegalArgumentException();
                String value=tree.textValue();
                if((nodeId.equals("profile") && value.length()>2400) || (nodeId.equals("compose") && value.isBlank())) throw new IllegalArgumentException();
                return value;
            }
            if(!"evidence".equals(nodeId) || !tree.isArray() || tree.size()>4) throw new IllegalArgumentException();
            List<LearningPlanEvidence> result=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(var item:tree) {
                if(!item.isObject() || item.size()!=3 || !item.path("documentId").isTextual()
                        || !item.path("title").isTextual() || !item.path("excerpt").isTextual()) throw new IllegalArgumentException();
                var evidence=mapper.treeToValue(item,LearningPlanEvidence.class);
                if(evidence.documentId().isBlank() || !ids.add(evidence.documentId()) || evidence.title().length()>120
                        || evidence.excerpt().isBlank() || evidence.excerpt().length()>1200) throw new IllegalArgumentException();
                result.add(evidence);
            }
            return List.copyOf(result);
        } catch(Exception e) { throw new IllegalArgumentException("INVALID_PREVIEW_OUTPUT"); }
    }
    public WorkflowOutcome outcome(DagExecutionResult result) {
        var view=preview.present(result);
        try { return new WorkflowOutcome(view.status(),mapper.writeValueAsString(view)); }
        catch(Exception e) { throw new IllegalArgumentException("INVALID_PREVIEW_RESULT"); }
    }
}

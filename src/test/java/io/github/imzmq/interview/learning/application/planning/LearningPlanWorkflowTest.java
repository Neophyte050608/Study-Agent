package io.github.imzmq.interview.learning.application.planning;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.agent.application.execution.FixedDagExecutor;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.imzmq.interview.learning.application.planning.LearningPlanPreviewTestFixtures.fixedService;
class LearningPlanWorkflowTest {
    @Test void fixedVersionCodecsRoundtripOnlyAllowedOutputsAndRejectWrongShape() {
        try(var executor=new FixedDagExecutor()) {
            var service=fixedService(executor,mock(LearningProfileOperations.class),t->List.of(),i->"plan");
            var operations=new LearningPlanNodeOperations(mock(LearningProfileOperations.class),t->List.of(),i->"plan");
            var factory=new LearningPlanFixedNodeFactory(operations);
            var workflow=new LearningPlanWorkflow(factory,service,new ObjectMapper());
            assertEquals(1,workflow.version());
            var evidence=List.of(new LearningPlanEvidence("doc","title","text"));
            assertEquals(evidence,workflow.decodeOutput("evidence",workflow.encodeOutput("evidence",evidence)));
            assertEquals("profile",workflow.decodeOutput("profile",workflow.encodeOutput("profile","profile")));
            assertThrows(IllegalArgumentException.class,()->workflow.decodeOutput("profile","{}"));
            assertThrows(IllegalArgumentException.class,()->workflow.nodes("owner","{\"topic\":\"Java\",\"userId\":\"other\"}"));
            var result=workflow.outcome(new DagExecutionResult("FAILED",Map.of(
                    "profile",new DagNodeResult(DagNodeStatus.FAILED,null,"NODE_FAILED"),
                    "evidence",new DagNodeResult(DagNodeStatus.SUCCEEDED,evidence,"OK"),
                    "compose",new DagNodeResult(DagNodeStatus.SUCCEEDED,"plan","OK"))));
            assertEquals("DEGRADED",result.status());assertTrue(result.resultJson().contains("PROFILE_UNAVAILABLE"));
        }
    }
}

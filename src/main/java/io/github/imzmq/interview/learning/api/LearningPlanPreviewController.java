package io.github.imzmq.interview.learning.api;

import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import io.github.imzmq.interview.learning.application.contract.DurableLearningPlanOperations;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.regex.Pattern;

import static io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException.Code.*;

@RestController
@RequestMapping("/api/learning/plan-previews")
public class LearningPlanPreviewController {
    private static final String BASE = "/api/learning/plan-previews/";
    private static final Pattern TASK_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final DurableLearningPlanOperations operations;
    private final UserIdentityQuery identity;

    public LearningPlanPreviewController(DurableLearningPlanOperations operations, UserIdentityQuery identity) {
        this.operations = operations;
        this.identity = identity;
    }

    @PostMapping
    public ResponseEntity<LearningPlanTaskResponse> submit(
            @RequestHeader("Idempotency-Key") String key, @RequestBody LearningPlanPreviewRequest body,
            HttpServletRequest request) {
        String owner = owner(request);
        body.validate(key);
        return accepted(operations.submit(owner, key, body.topic(), body.request()));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<LearningPlanTaskResponse> query(@PathVariable String taskId, HttpServletRequest request) {
        String owner = owner(request);
        taskId(taskId);
        return ResponseEntity.ok(LearningPlanTaskResponse.from(operations.query(owner, taskId)));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<LearningPlanTaskResponse> retry(@PathVariable String taskId,
            @RequestBody LearningPlanRetryRequest body, HttpServletRequest request) {
        String owner = owner(request);
        taskId(taskId);
        var nodes = body.selectedNodes();
        return accepted(operations.retry(owner, taskId, body.expectedRevision(), nodes));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable String taskId, HttpServletRequest request) {
        String owner = owner(request);
        taskId(taskId);
        operations.delete(owner, taskId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<LearningPlanTaskResponse> accepted(DurableLearningPlanView view) {
        return ResponseEntity.accepted().location(URI.create(BASE + view.taskId())).body(LearningPlanTaskResponse.from(view));
    }

    private String owner(HttpServletRequest request) {
        String principal = request.getUserPrincipal() == null ? "" : request.getUserPrincipal().getName();
        // Existing Identity trims/cleans/truncates at 64 chars. Reject lossy input instead of merging subjects.
        if (principal == null || !principal.equals(principal.trim()) || principal.length() > 64 || principal.chars().anyMatch(Character::isISOControl)) {
            throw new LearningPlanAccessException(UNAUTHENTICATED);
        }
        String owner = identity.resolveTrusted(principal, () -> "");
        if (owner == null || owner.isBlank()) throw new LearningPlanAccessException(UNAUTHENTICATED);
        return owner;
    }

    private void taskId(String id) {
        if (!TASK_ID.matcher(id).matches()) throw new LearningPlanAccessException(INVALID_INPUT);
    }
}

package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableExecutionOperations;
import io.github.imzmq.interview.learning.application.contract.DurableLearningPlanOperations;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException.Code.*;

@Service
public final class DurableLearningPlanService implements DurableLearningPlanOperations {
    private static final Pattern TASK_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> NODES = Set.of("profile", "evidence", "compose");
    private final DurableExecutionOperations execution;
    private final ObjectMapper mapper;
    private final LearningPlanTaskPresenter presenter;

    public DurableLearningPlanService(DurableExecutionOperations execution, ObjectMapper mapper) {
        this.execution = execution;
        this.mapper = mapper;
        this.presenter = new LearningPlanTaskPresenter(mapper);
    }

    @Override
    public DurableLearningPlanView submit(String owner, String key, String topic, String request) {
        owner(owner);
        if (key == null || key.isBlank() || key.length() > 120 || controls(key)) throw error(INVALID_INPUT);
        if (topic == null || topic.isBlank() || topic.trim().length() > 200) throw error(INVALID_PREVIEW_TOPIC);
        String instruction = request == null ? "" : request.trim();
        if (instruction.length() > 2000) throw error(INVALID_PREVIEW_REQUEST);
        return access(() -> {
            try {
                return presenter.present(execution.submit(owner, "LEARNING_PLAN_PREVIEW", 1, key,
                        mapper.writeValueAsString(new Input(topic.trim(), instruction))));
            } catch (JsonProcessingException e) {
                throw error(INVALID_INPUT);
            }
        });
    }

    @Override
    public DurableLearningPlanView query(String owner, String id) {
        identify(owner, id);
        return access(() -> presenter.present(execution.query(owner, id)));
    }

    @Override
    public DurableLearningPlanView retry(String owner, String id, int revision, Set<String> nodes) {
        identify(owner, id);
        if (revision < 0 || nodes == null || nodes.size() > 3 || nodes.stream().anyMatch(idNode -> idNode == null || !NODES.contains(idNode))) throw error(INVALID_INPUT);
        Set<String> selected = Set.copyOf(nodes);
        return access(() -> {
            presenter.requireWorkflow(execution.query(owner, id));
            return presenter.present(execution.retry(owner, id, revision, selected));
        });
    }

    @Override
    public void delete(String owner, String id) {
        identify(owner, id);
        access(() -> {
            presenter.requireWorkflow(execution.query(owner, id));
            execution.delete(owner, id);
            return null;
        });
    }

    private void identify(String owner, String id) {
        owner(owner);
        if (id == null || !TASK_ID.matcher(id).matches()) throw error(INVALID_INPUT);
    }

    private void owner(String owner) {
        if (owner == null || owner.isBlank()) throw error(UNAUTHENTICATED);
        if (owner.length() > 200 || controls(owner)) throw error(INVALID_INPUT);
    }

    private boolean controls(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private <T> T access(Supplier<T> action) {
        try {
            return action.get();
        } catch (LearningPlanAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            throw error(EXECUTION_UNAVAILABLE);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Only explicitly recognized runtime reasons become public error codes.
            throw switch (e.getMessage() == null ? "" : e.getMessage()) {
                case "EXECUTION_NOT_FOUND" -> error(EXECUTION_NOT_FOUND);
                case "IDEMPOTENCY_CONFLICT" -> error(IDEMPOTENCY_CONFLICT);
                case "REVISION_CONFLICT" -> error(REVISION_CONFLICT);
                case "EXECUTION_NOT_RETRYABLE" -> error(EXECUTION_NOT_RETRYABLE);
                case "EXECUTION_NOT_TERMINAL" -> error(EXECUTION_NOT_TERMINAL);
                case "RETRY_ROOT_NOT_FAILED" -> error(RETRY_ROOT_NOT_FAILED);
                case "ATTEMPT_BUDGET_EXHAUSTED" -> error(ATTEMPT_BUDGET_EXHAUSTED);
                case "EXECUTION_DISABLED" -> error(EXECUTION_DISABLED);
                default -> error(INTERNAL_ERROR);
            };
        } catch (RuntimeException e) {
            throw error(INTERNAL_ERROR);
        }
    }

    private LearningPlanAccessException error(LearningPlanAccessException.Code code) {
        return new LearningPlanAccessException(code);
    }

    private record Input(String topic, String request) { }
}

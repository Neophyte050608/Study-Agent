package io.github.imzmq.interview.architecture.fixture.learning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableExecutionOperations;

public class ControllerDependingOnExecution {
    private final DurableExecutionOperations execution;
    private final ObjectMapper mapper;

    public ControllerDependingOnExecution(DurableExecutionOperations execution, ObjectMapper mapper) {
        this.execution = execution;
        this.mapper = mapper;
    }
}

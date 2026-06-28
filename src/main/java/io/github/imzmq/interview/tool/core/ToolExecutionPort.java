package io.github.imzmq.interview.tool.core;

public interface ToolExecutionPort {

    ToolExecutionResult execute(ToolExecutionRequest request);

    ToolDefinition definition(String toolId);
}

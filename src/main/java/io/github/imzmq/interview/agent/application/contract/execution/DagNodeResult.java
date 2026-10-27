package io.github.imzmq.interview.agent.application.contract.execution;

public record DagNodeResult(DagNodeStatus status, Object output, String code) { }

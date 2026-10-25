package io.github.imzmq.interview.architecturefixturetarget.application;

import io.github.imzmq.interview.architecturefixturesource.application.contract.SourcePublicOperation;

public final class ReverseContractConsumer {

    private final SourcePublicOperation operation;

    public ReverseContractConsumer(SourcePublicOperation operation) {
        this.operation = operation;
    }
}

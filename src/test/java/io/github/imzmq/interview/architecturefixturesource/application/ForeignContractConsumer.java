package io.github.imzmq.interview.architecturefixturesource.application;

import io.github.imzmq.interview.architecturefixturetarget.application.contract.PublicOperation;

public class ForeignContractConsumer {

    private final PublicOperation operation;

    public ForeignContractConsumer(PublicOperation operation) {
        this.operation = operation;
    }
}

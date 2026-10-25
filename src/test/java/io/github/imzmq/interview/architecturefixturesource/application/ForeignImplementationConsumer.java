package io.github.imzmq.interview.architecturefixturesource.application;

import io.github.imzmq.interview.architecturefixturetarget.application.InternalApplicationService;

public class ForeignImplementationConsumer {

    private final InternalApplicationService service;

    public ForeignImplementationConsumer(InternalApplicationService service) {
        this.service = service;
    }
}

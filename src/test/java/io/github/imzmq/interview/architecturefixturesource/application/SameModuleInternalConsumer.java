package io.github.imzmq.interview.architecturefixturesource.application;

public class SameModuleInternalConsumer {

    private final LocalApplicationService service;

    public SameModuleInternalConsumer(LocalApplicationService service) {
        this.service = service;
    }
}

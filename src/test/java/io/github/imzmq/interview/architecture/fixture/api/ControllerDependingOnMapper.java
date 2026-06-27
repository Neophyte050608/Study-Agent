package io.github.imzmq.interview.architecture.fixture.api;

import io.github.imzmq.interview.architecture.fixture.infrastructure.persistence.SamplePersistenceMapper;

public class ControllerDependingOnMapper {
    private final SamplePersistenceMapper samplePersistenceMapper = null;
}

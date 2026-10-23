package io.github.imzmq.interview.architecturefixturesource.application;

import io.github.imzmq.interview.architecturefixturetarget.infrastructure.persistence.ForeignPersistenceDO;

public final class CrossModulePersistenceConsumer {

    private final ForeignPersistenceDO persistenceObject;

    public CrossModulePersistenceConsumer(ForeignPersistenceDO persistenceObject) {
        this.persistenceObject = persistenceObject;
    }

    public ForeignPersistenceDO persistenceObject() {
        return persistenceObject;
    }
}

package io.github.imzmq.interview.model;

import io.github.imzmq.interview.model.core.ModelRoutingProperties;
import io.github.imzmq.interview.model.application.DynamicModelFactory;
import io.github.imzmq.interview.model.probe.ModelProbeAwaiter;
import io.github.imzmq.interview.model.probe.ModelHealthProbeScheduler;
import io.github.imzmq.interview.model.catalog.ModelCandidateService;
import io.github.imzmq.interview.model.state.ModelHealthStore;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModelHealthProbeSchedulerTest {

    @Test
    void shouldSkipScheduledProbeWhenRoutingDisabled() {
        ModelCandidateService modelCandidateService = mock(ModelCandidateService.class);
        ModelRoutingProperties properties = new ModelRoutingProperties();
        properties.setEnabled(false);
        DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);
        ModelProbeAwaiter modelProbeAwaiter = mock(ModelProbeAwaiter.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);

        ModelHealthProbeScheduler scheduler = new ModelHealthProbeScheduler(
                modelCandidateService,
                properties,
                dynamicModelFactory,
                modelProbeAwaiter,
                modelHealthStore
        );

        scheduler.probePrimaryModels();

        verifyNoInteractions(modelCandidateService, dynamicModelFactory, modelProbeAwaiter, modelHealthStore);
    }
}





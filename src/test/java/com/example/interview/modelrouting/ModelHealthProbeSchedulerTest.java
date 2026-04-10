package com.example.interview.modelrouting;

import com.example.interview.service.DynamicModelFactory;
import com.example.interview.service.model.ModelCandidateService;
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
        FirstTokenProbeInvoker firstTokenProbeInvoker = mock(FirstTokenProbeInvoker.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);

        ModelHealthProbeScheduler scheduler = new ModelHealthProbeScheduler(
                modelCandidateService,
                properties,
                dynamicModelFactory,
                firstTokenProbeInvoker,
                modelHealthStore
        );

        scheduler.probePrimaryModels();

        verifyNoInteractions(modelCandidateService, dynamicModelFactory, firstTokenProbeInvoker, modelHealthStore);
    }
}

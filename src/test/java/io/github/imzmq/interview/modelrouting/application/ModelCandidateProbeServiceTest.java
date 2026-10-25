package io.github.imzmq.interview.modelrouting.application;

import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.probe.ModelProbeAwaiter;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCandidateProbeServiceTest {

    @Test
    void returnsEmptyWhenTheCandidateDoesNotExist() {
        ModelCandidateService candidates = mock(ModelCandidateService.class);
        ModelCandidateProbeService service = new ModelCandidateProbeService(
                candidates,
                mock(DynamicModelFactory.class),
                mock(ModelProbeAwaiter.class),
                mock(ModelHealthStore.class)
        );
        when(candidates.getById(404L)).thenReturn(null);

        assertThat(service.probe(404L)).isEmpty();
    }

    @Test
    void retainsCandidateNameWhenModelCreationFails() {
        ModelCandidateService candidates = mock(ModelCandidateService.class);
        DynamicModelFactory models = mock(DynamicModelFactory.class);
        ModelCandidateProbeService service = new ModelCandidateProbeService(
                candidates,
                models,
                mock(ModelProbeAwaiter.class),
                mock(ModelHealthStore.class)
        );
        ModelCandidateDO candidate = new ModelCandidateDO();
        candidate.setName("unavailable-model");
        when(candidates.getById(7L)).thenReturn(candidate);
        when(models.getByCandidate(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        org.assertj.core.api.ThrowableAssert.ThrowingCallable call = () -> service.probe(7L);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(call))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("无法创建模型实例");
    }
}

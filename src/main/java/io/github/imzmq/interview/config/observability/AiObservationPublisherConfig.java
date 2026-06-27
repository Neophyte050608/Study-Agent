package io.github.imzmq.interview.config.observability;

import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AiObservationPublisherConfig {

    @Bean
    @ConditionalOnMissingBean(AiObservationPublisher.class)
    public AiObservationPublisher noopAiObservationPublisher() {
        return new NoopAiObservationPublisher();
    }
}

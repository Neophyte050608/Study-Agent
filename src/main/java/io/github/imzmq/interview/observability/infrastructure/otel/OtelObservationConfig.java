package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.config.observability.AiObservationPublisherConfig;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = AiObservationPublisherConfig.class)
@EnableConfigurationProperties(OtelObservationProperties.class)
public class OtelObservationConfig {

    private static final Logger log = LoggerFactory.getLogger(OtelObservationConfig.class);
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    @Bean
    @ConditionalOnMissingBean(AiObservationPublisher.class)
    public AiObservationPublisher aiObservationPublisher(OtelObservationProperties properties) {
        if (properties == null || !properties.isConfigured()) {
            return new NoopAiObservationPublisher();
        }

        try {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(properties.getEndpoint())
                    .addHeader("Authorization", properties.basicAuthorizationHeader())
                    .build();
            Resource resource = Resource.getDefault()
                    .merge(Resource.create(Attributes.of(SERVICE_NAME, properties.getServiceName())));
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            return new OtelAiObservationPublisher(
                    openTelemetry.getTracer("study-agent-ai-observability"),
                    new OtelObservationMapper());
        } catch (RuntimeException ex) {
            log.warn("Falling back to noop AI observation publisher because OpenTelemetry setup failed: {}",
                    ex.getClass().getSimpleName());
            return new NoopAiObservationPublisher();
        }
    }
}

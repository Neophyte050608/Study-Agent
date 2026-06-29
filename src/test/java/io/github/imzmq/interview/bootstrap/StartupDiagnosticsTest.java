package io.github.imzmq.interview.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class StartupDiagnosticsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StartupDiagnosticsProperties.class, StartupDiagnostics.class);

    @Test
    void buildsSnapshotFromEnvironmentAndProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local-lite",
                        "app.knowledge.retrieval.warmup-enabled=false",
                        "app.startup.model-preheat-enabled=true",
                        "app.model-routing.probe.enabled=true",
                        "app.dream.enabled=true",
                        "im.qq.use-ws=true",
                        "im.feishu.use-ws=true",
                        "app.observability.external.enabled=true",
                        "app.observability.external.provider=langfuse-otel")
                .run(context -> {
                    StartupDiagnostics diagnostics = context.getBean(StartupDiagnostics.class);
                    StartupDiagnostics.StartupSnapshot snapshot = diagnostics.snapshot();

                    assertThat(snapshot.activeProfiles()).containsExactly("local-lite");
                    assertThat(snapshot.ragWarmupEnabled()).isFalse();
                    assertThat(snapshot.modelPreheatEnabled()).isTrue();
                    assertThat(snapshot.modelProbeEnabled()).isTrue();
                    assertThat(snapshot.dreamEnabled()).isTrue();
                    assertThat(snapshot.qqWsEnabled()).isTrue();
                    assertThat(snapshot.feishuWsEnabled()).isTrue();
                    assertThat(snapshot.externalObservabilityEnabled()).isTrue();
                    assertThat(snapshot.externalObservabilityProvider()).isEqualTo("langfuse-otel");
                    assertThat(snapshot.externalObservability()).isEqualTo("langfuse-otel(enabled)");
                });
    }

    @Test
    void usesExternalObservabilityDefaults() {
        contextRunner.run(context -> {
            StartupDiagnostics diagnostics = context.getBean(StartupDiagnostics.class);
            StartupDiagnostics.StartupSnapshot snapshot = diagnostics.snapshot();

            assertThat(snapshot.externalObservabilityEnabled()).isFalse();
            assertThat(snapshot.externalObservabilityProvider()).isEqualTo("noop");
            assertThat(snapshot.externalObservability()).isEqualTo("noop(disabled)");
        });
    }

    @Test
    void readyEventLoggingDoesNotThrow() {
        contextRunner.run(context -> {
            StartupDiagnostics diagnostics = context.getBean(StartupDiagnostics.class);
            diagnostics.onReady(event(context));
        });
    }

    private ApplicationReadyEvent event(ConfigurableApplicationContext context) {
        return new ApplicationReadyEvent(new org.springframework.boot.SpringApplication(StartupDiagnosticsTest.class),
                new String[0], context, java.time.Duration.ZERO);
    }
}

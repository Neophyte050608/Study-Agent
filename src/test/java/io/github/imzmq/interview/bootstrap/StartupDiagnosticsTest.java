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
                        "app.startup.model-preheat-enabled=false",
                        "app.model-routing.probe.enabled=false",
                        "app.dream.enabled=false",
                        "im.qq.use-ws=false",
                        "im.feishu.use-ws=true",
                        "app.observability.external.enabled=true",
                        "app.observability.external.provider=langfuse-otel")
                .run(context -> {
                    StartupDiagnostics diagnostics = context.getBean(StartupDiagnostics.class);
                    StartupDiagnostics.StartupSnapshot snapshot = diagnostics.snapshot();

                    assertThat(snapshot.activeProfiles()).containsExactly("local-lite");
                    assertThat(snapshot.ragWarmupEnabled()).isFalse();
                    assertThat(snapshot.modelPreheatEnabled()).isFalse();
                    assertThat(snapshot.modelProbeEnabled()).isFalse();
                    assertThat(snapshot.dreamEnabled()).isFalse();
                    assertThat(snapshot.qqWsEnabled()).isFalse();
                    assertThat(snapshot.feishuWsEnabled()).isTrue();
                    assertThat(snapshot.externalObservability()).isEqualTo("langfuse-otel(enabled)");
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

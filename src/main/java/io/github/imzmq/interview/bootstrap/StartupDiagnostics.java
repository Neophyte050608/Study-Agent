package io.github.imzmq.interview.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class StartupDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final Environment environment;
    private final StartupDiagnosticsProperties properties;

    public StartupDiagnostics(Environment environment, StartupDiagnosticsProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        StartupSnapshot snapshot = snapshot();
        log.info("Study-Agent startup: profiles={}, ragWarmup={}, modelPreheat={}, modelProbe={}, dream={}, qqWs={}, feishuWs={}, externalObservability={}",
                Arrays.toString(snapshot.activeProfiles()),
                enabled(snapshot.ragWarmupEnabled()),
                enabled(snapshot.modelPreheatEnabled()),
                enabled(snapshot.modelProbeEnabled()),
                enabled(snapshot.dreamEnabled()),
                enabled(snapshot.qqWsEnabled()),
                enabled(snapshot.feishuWsEnabled()),
                snapshot.externalObservability());
    }

    public StartupSnapshot snapshot() {
        String[] activeProfiles = environment.getActiveProfiles();
        return new StartupSnapshot(
                activeProfiles.length == 0 ? new String[] {"default"} : activeProfiles,
                properties.isRagWarmupEnabled(),
                properties.isModelPreheatEnabled(),
                properties.isModelProbeEnabled(),
                properties.isDreamEnabled(),
                properties.isQqWsEnabled(),
                properties.isFeishuWsEnabled(),
                properties.isExternalObservabilityEnabled(),
                properties.getExternalObservabilityProvider());
    }

    private String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }

    public record StartupSnapshot(
            String[] activeProfiles,
            boolean ragWarmupEnabled,
            boolean modelPreheatEnabled,
            boolean modelProbeEnabled,
            boolean dreamEnabled,
            boolean qqWsEnabled,
            boolean feishuWsEnabled,
            boolean externalObservabilityEnabled,
            String externalObservabilityProvider) {

        public String externalObservability() {
            return externalObservabilityProvider + (externalObservabilityEnabled ? "(enabled)" : "(disabled)");
        }
    }
}

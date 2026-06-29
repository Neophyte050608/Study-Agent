package io.github.imzmq.interview.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StartupDiagnosticsProperties {

    private final boolean ragWarmupEnabled;
    private final boolean modelPreheatEnabled;
    private final boolean modelProbeEnabled;
    private final boolean dreamEnabled;
    private final boolean qqWsEnabled;
    private final boolean feishuWsEnabled;
    private final boolean externalObservabilityEnabled;
    private final String externalObservabilityProvider;

    public StartupDiagnosticsProperties(
            @Value("${app.knowledge.retrieval.warmup-enabled:true}") boolean ragWarmupEnabled,
            @Value("${app.startup.model-preheat-enabled:false}") boolean modelPreheatEnabled,
            @Value("${app.model-routing.probe.enabled:false}") boolean modelProbeEnabled,
            @Value("${app.dream.enabled:false}") boolean dreamEnabled,
            @Value("${im.qq.use-ws:false}") boolean qqWsEnabled,
            @Value("${im.feishu.use-ws:false}") boolean feishuWsEnabled,
            @Value("${app.observability.external.enabled:false}") boolean externalObservabilityEnabled,
            @Value("${app.observability.external.provider:noop}") String externalObservabilityProvider) {
        this.ragWarmupEnabled = ragWarmupEnabled;
        this.modelPreheatEnabled = modelPreheatEnabled;
        this.modelProbeEnabled = modelProbeEnabled;
        this.dreamEnabled = dreamEnabled;
        this.qqWsEnabled = qqWsEnabled;
        this.feishuWsEnabled = feishuWsEnabled;
        this.externalObservabilityEnabled = externalObservabilityEnabled;
        this.externalObservabilityProvider = externalObservabilityProvider == null || externalObservabilityProvider.isBlank()
                ? "noop" : externalObservabilityProvider.trim();
    }

    public boolean isRagWarmupEnabled() {
        return ragWarmupEnabled;
    }

    public boolean isModelPreheatEnabled() {
        return modelPreheatEnabled;
    }

    public boolean isModelProbeEnabled() {
        return modelProbeEnabled;
    }

    public boolean isDreamEnabled() {
        return dreamEnabled;
    }

    public boolean isQqWsEnabled() {
        return qqWsEnabled;
    }

    public boolean isFeishuWsEnabled() {
        return feishuWsEnabled;
    }

    public boolean isExternalObservabilityEnabled() {
        return externalObservabilityEnabled;
    }

    public String getExternalObservabilityProvider() {
        return externalObservabilityProvider;
    }
}

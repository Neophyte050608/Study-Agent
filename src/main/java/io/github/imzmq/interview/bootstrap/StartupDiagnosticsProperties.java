package io.github.imzmq.interview.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StartupDiagnosticsProperties {

    private static final String EXTERNAL_OBSERVABILITY_PROVIDER = "langfuse-otel";

    private final boolean ragWarmupEnabled;
    private final boolean modelPreheatEnabled;
    private final boolean modelProbeEnabled;
    private final boolean dreamEnabled;
    private final boolean qqWsEnabled;
    private final boolean feishuWsEnabled;
    private final boolean externalObservabilityEnabled;
    private final String externalObservabilityProvider;
    private final String externalObservabilityEndpoint;
    private final String externalObservabilityPublicKey;
    private final String externalObservabilitySecretKey;

    public StartupDiagnosticsProperties(
            @Value("${app.knowledge.retrieval.warmup-enabled:true}") boolean ragWarmupEnabled,
            @Value("${app.startup.model-preheat-enabled:false}") boolean modelPreheatEnabled,
            @Value("${app.model-routing.probe.enabled:false}") boolean modelProbeEnabled,
            @Value("${app.dream.enabled:false}") boolean dreamEnabled,
            @Value("${im.qq.use-ws:false}") boolean qqWsEnabled,
            @Value("${im.feishu.use-ws:false}") boolean feishuWsEnabled,
            @Value("${app.observability.external.enabled:false}") boolean externalObservabilityEnabled,
            @Value("${app.observability.external.provider:langfuse-otel}") String externalObservabilityProvider,
            @Value("${app.observability.external.endpoint:}") String externalObservabilityEndpoint,
            @Value("${app.observability.external.public-key:}") String externalObservabilityPublicKey,
            @Value("${app.observability.external.secret-key:}") String externalObservabilitySecretKey) {
        this.ragWarmupEnabled = ragWarmupEnabled;
        this.modelPreheatEnabled = modelPreheatEnabled;
        this.modelProbeEnabled = modelProbeEnabled;
        this.dreamEnabled = dreamEnabled;
        this.qqWsEnabled = qqWsEnabled;
        this.feishuWsEnabled = feishuWsEnabled;
        this.externalObservabilityEnabled = externalObservabilityEnabled;
        this.externalObservabilityProvider = trimOrDefault(
                externalObservabilityProvider,
                EXTERNAL_OBSERVABILITY_PROVIDER);
        this.externalObservabilityEndpoint = trimOrDefault(externalObservabilityEndpoint, "");
        this.externalObservabilityPublicKey = trimOrDefault(externalObservabilityPublicKey, "");
        this.externalObservabilitySecretKey = trimOrDefault(externalObservabilitySecretKey, "");
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
        return isExternalObservabilityConfigured();
    }

    public String getExternalObservabilityProvider() {
        return externalObservabilityProvider;
    }

    public boolean isExternalObservabilityConfigured() {
        return externalObservabilityEnabled
                && EXTERNAL_OBSERVABILITY_PROVIDER.equalsIgnoreCase(externalObservabilityProvider)
                && hasText(externalObservabilityEndpoint)
                && hasText(externalObservabilityPublicKey)
                && hasText(externalObservabilitySecretKey);
    }

    private static String trimOrDefault(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        return trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

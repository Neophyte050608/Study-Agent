package io.github.imzmq.interview.observability.infrastructure.otel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ConfigurationProperties(prefix = "app.observability.external")
public class OtelObservationProperties {

    private static final String DEFAULT_PROVIDER = "langfuse-otel";
    private static final String DEFAULT_SERVICE_NAME = "study-agent";

    private boolean enabled = false;
    private String provider = DEFAULT_PROVIDER;
    private String endpoint = "";
    private String publicKey = "";
    private String secretKey = "";
    private String serviceName = DEFAULT_SERVICE_NAME;
    private boolean exportPrompts = false;
    private boolean exportCompletions = false;

    public boolean isConfigured() {
        return enabled
                && DEFAULT_PROVIDER.equalsIgnoreCase(provider)
                && hasText(endpoint)
                && hasText(publicKey)
                && hasText(secretKey);
    }

    public String basicAuthorizationHeader() {
        String credentials = publicKey + ":" + secretKey;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = trimOrDefault(provider, DEFAULT_PROVIDER);
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = trimOrDefault(endpoint, "");
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = trimOrDefault(publicKey, "");
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = trimOrDefault(secretKey, "");
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = trimOrDefault(serviceName, DEFAULT_SERVICE_NAME);
    }

    public boolean isExportPrompts() {
        return exportPrompts;
    }

    public void setExportPrompts(boolean exportPrompts) {
        this.exportPrompts = exportPrompts;
    }

    public boolean isExportCompletions() {
        return exportCompletions;
    }

    public void setExportCompletions(boolean exportCompletions) {
        this.exportCompletions = exportCompletions;
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

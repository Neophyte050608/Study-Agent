package io.github.imzmq.interview.observability.infrastructure.otel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelObservationPropertiesTest {

    @Test
    void shouldBeDisabledAndIncompleteByDefault() {
        OtelObservationProperties properties = new OtelObservationProperties();

        assertFalse(properties.isEnabled());
        assertEquals("langfuse-otel", properties.getProvider());
        assertEquals("", properties.getEndpoint());
        assertEquals("", properties.getPublicKey());
        assertEquals("", properties.getSecretKey());
        assertEquals("study-agent", properties.getServiceName());
        assertFalse(properties.isExportPrompts());
        assertFalse(properties.isExportCompletions());
        assertFalse(properties.isConfigured());
    }

    @Test
    void shouldBeConfiguredWhenEnabledLangfuseOtelAndRequiredSecretsArePresent() {
        OtelObservationProperties properties = new OtelObservationProperties();

        properties.setEnabled(true);
        properties.setProvider(" Langfuse-OTEL ");
        properties.setEndpoint(" https://cloud.langfuse.com/api/public/otel ");
        properties.setPublicKey(" pk-lf-test ");
        properties.setSecretKey(" sk-lf-test ");
        properties.setServiceName(" study-agent-local ");
        properties.setExportPrompts(true);
        properties.setExportCompletions(true);

        assertTrue(properties.isConfigured());
        assertEquals("Langfuse-OTEL", properties.getProvider());
        assertEquals("https://cloud.langfuse.com/api/public/otel", properties.getEndpoint());
        assertEquals("pk-lf-test", properties.getPublicKey());
        assertEquals("sk-lf-test", properties.getSecretKey());
        assertEquals("study-agent-local", properties.getServiceName());
        assertTrue(properties.isExportPrompts());
        assertTrue(properties.isExportCompletions());
    }

    @Test
    void shouldNotBeConfiguredWhenProviderIsUnsupported() {
        OtelObservationProperties properties = completeProperties();
        properties.setProvider("other-provider");

        assertFalse(properties.isConfigured());
    }

    @Test
    void shouldNotBeConfiguredWhenSecretKeyIsMissing() {
        OtelObservationProperties properties = completeProperties();
        properties.setSecretKey("   ");

        assertFalse(properties.isConfigured());
        assertEquals("", properties.getSecretKey());
    }

    @Test
    void shouldBuildBasicAuthorizationHeaderFromPublicAndSecretKeys() {
        OtelObservationProperties properties = new OtelObservationProperties();
        properties.setPublicKey("pk-lf-test");
        properties.setSecretKey("sk-lf-test");

        assertEquals("Basic cGstbGYtdGVzdDpzay1sZi10ZXN0", properties.basicAuthorizationHeader());
    }

    @Test
    void shouldFallbackToDefaultsWhenBlankOrNullValuesAreSet() {
        OtelObservationProperties properties = completeProperties();

        properties.setProvider(null);
        properties.setEndpoint(null);
        properties.setPublicKey(" ");
        properties.setSecretKey(null);
        properties.setServiceName(" ");

        assertEquals("langfuse-otel", properties.getProvider());
        assertEquals("", properties.getEndpoint());
        assertEquals("", properties.getPublicKey());
        assertEquals("", properties.getSecretKey());
        assertEquals("study-agent", properties.getServiceName());
        assertFalse(properties.isConfigured());
    }

    private OtelObservationProperties completeProperties() {
        OtelObservationProperties properties = new OtelObservationProperties();
        properties.setEnabled(true);
        properties.setProvider("langfuse-otel");
        properties.setEndpoint("https://cloud.langfuse.com/api/public/otel");
        properties.setPublicKey("pk-lf-test");
        properties.setSecretKey("sk-lf-test");
        return properties;
    }
}

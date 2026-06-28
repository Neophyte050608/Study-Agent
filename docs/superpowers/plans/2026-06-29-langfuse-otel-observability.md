# Langfuse OpenTelemetry Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a disabled-by-default OpenTelemetry adapter that exports sanitized `AiObservationEvent` spans to Langfuse without coupling business code to Langfuse or OTel APIs.

**Architecture:** Keep `AiObservationPublisher` as the application port. Add `observability.infrastructure.otel` for properties, mapper, publisher, and Spring configuration; when disabled or incomplete, the existing Noop publisher remains the fallback.

**Tech Stack:** Java 21, Spring Boot configuration properties, OpenTelemetry Java API/SDK/OTLP HTTP exporter, JUnit 5, AssertJ, ApplicationContextRunner.

---

## File Structure

- Modify `pom.xml` — add OpenTelemetry version property and API/SDK/OTLP dependencies with explicit `${opentelemetry.version}` versions.
- Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationProperties.java` — configuration and Basic Auth helper.
- Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapper.java` — maps sanitized internal events to stable OTel attributes.
- Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisher.java` — creates best-effort spans from `AiObservationEvent`.
- Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfig.java` — Spring config creating OTel SDK/exporter-backed publisher when enabled and complete, otherwise Noop fallback.
- Modify `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — register `OtelObservationConfig` before `AiObservationPublisherConfig` so the OTel publisher has the first chance to satisfy `AiObservationPublisher`.
- Create tests under `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/`.
- Modify `src/test/java/io/github/imzmq/interview/config/observability/AiObservationPublisherConfigTest.java` — verify Noop fallback and OTel override behavior.
- Modify `docs/development/observability-guidelines.md` — document Langfuse via OTEL config and safety rules.

---

### Task 1: Add OpenTelemetry Dependencies and Properties

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationProperties.java`
- Test: `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationPropertiesTest.java`

- [ ] **Step 1: Add failing properties test**

Create `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationPropertiesTest.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtelObservationPropertiesTest {

    @Test
    void defaultsAreDisabledAndIncomplete() {
        OtelObservationProperties properties = new OtelObservationProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getProvider()).isEqualTo("langfuse-otel");
        assertThat(properties.getEndpoint()).isEmpty();
        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.isExportPrompts()).isFalse();
        assertThat(properties.isExportCompletions()).isFalse();
    }

    @Test
    void configuredRequiresEnabledProviderEndpointAndKeys() {
        OtelObservationProperties properties = new OtelObservationProperties();
        properties.setEnabled(true);
        properties.setEndpoint(" https://us.cloud.langfuse.com/api/public/otel ");
        properties.setPublicKey(" pk-lf-public ");
        properties.setSecretKey(" sk-lf-secret ");

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.getEndpoint()).isEqualTo("https://us.cloud.langfuse.com/api/public/otel");
        assertThat(properties.getPublicKey()).isEqualTo("pk-lf-public");
        assertThat(properties.getSecretKey()).isEqualTo("sk-lf-secret");
    }

    @Test
    void configuredRejectsUnsupportedProviderAndMissingSecrets() {
        OtelObservationProperties properties = new OtelObservationProperties();
        properties.setEnabled(true);
        properties.setProvider("other");
        properties.setEndpoint("https://cloud.langfuse.com/api/public/otel");
        properties.setPublicKey("pk");
        properties.setSecretKey("sk");

        assertThat(properties.isConfigured()).isFalse();

        properties.setProvider("langfuse-otel");
        properties.setSecretKey(" ");

        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void buildsBasicAuthorizationHeader() {
        OtelObservationProperties properties = new OtelObservationProperties();
        properties.setPublicKey("pk-test");
        properties.setSecretKey("sk-test");

        assertThat(properties.basicAuthorizationHeader()).isEqualTo("Basic cGstdGVzdDpzay10ZXN0");
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -q -Dtest=OtelObservationPropertiesTest test
```

Expected: compilation fails because `OtelObservationProperties` does not exist.

- [ ] **Step 3: Add OpenTelemetry dependencies**

Modify `pom.xml`:

```xml
<properties>
    ...
    <opentelemetry.version>1.63.0</opentelemetry.version>
</properties>
```

Add dependencies:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>
```

Do not add the OpenTelemetry BOM. Keep OTel version management scoped to these direct dependencies so transitive users such as RocketMQ keep their existing resolved dependency ranges.

- [ ] **Step 4: Implement properties**

Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationProperties.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

@ConfigurationProperties(prefix = "app.observability.external")
public class OtelObservationProperties {

    private boolean enabled = false;
    private String provider = "langfuse-otel";
    private String endpoint = "";
    private String publicKey = "";
    private String secretKey = "";
    private String serviceName = "study-agent";
    private boolean exportPrompts = false;
    private boolean exportCompletions = false;

    public boolean isConfigured() {
        return enabled
                && "langfuse-otel".equals(provider.toLowerCase(Locale.ROOT))
                && !endpoint.isBlank()
                && !publicKey.isBlank()
                && !secretKey.isBlank();
    }

    public String basicAuthorizationHeader() {
        String token = publicKey + ":" + secretKey;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = normalize(provider, "langfuse-otel"); }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = normalize(endpoint, ""); }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = normalize(publicKey, ""); }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = normalize(secretKey, ""); }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = normalize(serviceName, "study-agent"); }
    public boolean isExportPrompts() { return exportPrompts; }
    public void setExportPrompts(boolean exportPrompts) { this.exportPrompts = exportPrompts; }
    public boolean isExportCompletions() { return exportCompletions; }
    public void setExportCompletions(boolean exportCompletions) { this.exportCompletions = exportCompletions; }

    private String normalize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }
}
```

- [ ] **Step 5: Run the focused test**

Run:

```bash
mvn -q -Dtest=OtelObservationPropertiesTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationProperties.java src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationPropertiesTest.java
git commit -m "feat: add otel observation properties"
```

---

### Task 2: Add Observation Mapper

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapper.java`
- Test: `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapperTest.java`

- [ ] **Step 1: Write mapper tests**

Create `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapperTest.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelObservationMapperTest {

    @Test
    void mapsSafeFieldsToOtelAttributes() {
        OtelObservationMapper mapper = new OtelObservationMapper();
        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1", "node-1", "retrieval", "knowledge retrieval", "success",
                Map.of(
                        "model", "glm-4",
                        "provider", "zhipu",
                        "latencyMs", "123",
                        "promptTokens", "10",
                        "completionTokens", "20",
                        "totalTokens", "30",
                        "estimatedCost", "0.001",
                        "docCount", "4",
                        "retrievalMode", "hybrid",
                        "taskType", "KNOWLEDGE_QA",
                        "routeSource", "intent"
                )
        );

        Map<String, String> attributes = mapper.toAttributes(event);

        assertThat(attributes)
                .containsEntry("ai.event_type", "rag.node")
                .containsEntry("ai.category", "retrieval")
                .containsEntry("ai.status", "success")
                .containsEntry("ai.model", "glm-4")
                .containsEntry("ai.provider", "zhipu")
                .containsEntry("ai.latency_ms", "123")
                .containsEntry("ai.prompt_tokens", "10")
                .containsEntry("ai.completion_tokens", "20")
                .containsEntry("ai.total_tokens", "30")
                .containsEntry("ai.estimated_cost", "0.001")
                .containsEntry("rag.doc_count", "4")
                .containsEntry("rag.retrieval_mode", "hybrid")
                .containsEntry("agent.task_type", "KNOWLEDGE_QA")
                .containsEntry("agent.route_source", "intent");
    }

    @Test
    void doesNotMapSensitiveIdentifiersOrRawText() {
        OtelObservationMapper mapper = new OtelObservationMapper();
        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1", "node-1", "retrieval", "knowledge retrieval", "failed",
                Map.of(
                        "userId", "user-1",
                        "sessionId", "session-1",
                        "taskId", "task-1",
                        "candidateId", "candidate-1",
                        "prompt", "raw prompt",
                        "completion", "raw completion",
                        "error", "apiKey=secret",
                        "model", "glm-4"
                )
        );

        Map<String, String> attributes = mapper.toAttributes(event);

        assertThat(attributes).containsEntry("ai.model", "glm-4");
        assertThat(attributes).doesNotContainKeys(
                "userId", "sessionId", "taskId", "candidateId", "prompt", "completion", "error"
        );
        assertThat(attributes.toString()).doesNotContain("raw prompt", "raw completion", "secret");
    }

    @Test
    void buildsControlledSpanName() {
        OtelObservationMapper mapper = new OtelObservationMapper();
        AiObservationEvent event = AiObservationEvent.ragNode("trace", "node", "retrieval", "user text", "success", Map.of());

        assertThat(mapper.spanName(event)).isEqualTo("ai.rag_node.retrieval");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=OtelObservationMapperTest test
```

Expected: compilation fails because mapper does not exist.

- [ ] **Step 3: Implement mapper**

Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapper.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public class OtelObservationMapper {

    private static final String DEFAULT_SPAN_NAME = "ai.event";
    private static final int MAX_SPAN_NAME_PART_LENGTH = 64;

    private static final Map<String, String> ATTRIBUTE_MAPPING = Map.ofEntries(
            Map.entry("model", "ai.model"),
            Map.entry("provider", "ai.provider"),
            Map.entry("latencyMs", "ai.latency_ms"),
            Map.entry("completionMs", "ai.latency_ms"),
            Map.entry("promptTokens", "ai.prompt_tokens"),
            Map.entry("completionTokens", "ai.completion_tokens"),
            Map.entry("totalTokens", "ai.total_tokens"),
            Map.entry("estimatedCost", "ai.estimated_cost"),
            Map.entry("docCount", "rag.doc_count"),
            Map.entry("retrievalMode", "rag.retrieval_mode"),
            Map.entry("taskType", "agent.task_type"),
            Map.entry("routeSource", "agent.route_source")
    );

    public String spanName(AiObservationEvent event) {
        if (event == null) {
            return DEFAULT_SPAN_NAME;
        }

        String eventType = sanitizeNamePart(event.eventType());
        String category = sanitizeNamePart(event.category());
        if (eventType.isEmpty() || category.isEmpty()) {
            return DEFAULT_SPAN_NAME;
        }
        return "ai." + eventType + "." + category;
    }

    public Map<String, String> toAttributes(AiObservationEvent event) {
        if (event == null) {
            return Map.of();
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "ai.event_type", event.eventType());
        put(attributes, "ai.category", event.category());
        put(attributes, "ai.status", event.status());
        event.attributes().forEach((key, value) -> {
            String externalKey = ATTRIBUTE_MAPPING.get(key);
            if (externalKey != null) {
                put(attributes, externalKey, value);
            }
        });
        return Map.copyOf(attributes);
    }

    private void put(Map<String, String> target, String key, Object value) {
        String text = safe(value == null ? "" : String.valueOf(value));
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sanitizeNamePart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            char replacement = safeSpanNameChar(current);
            if (replacement == '_') {
                if (!previousUnderscore) {
                    sanitized.append(replacement);
                    previousUnderscore = true;
                }
                continue;
            }
            sanitized.append(replacement);
            previousUnderscore = false;
        }
        String part = stripBoundaryUnderscores(sanitized.toString());
        if (part.length() > MAX_SPAN_NAME_PART_LENGTH) {
            part = stripBoundaryUnderscores(part.substring(0, MAX_SPAN_NAME_PART_LENGTH));
        }
        return part;
    }

    private char safeSpanNameChar(char value) {
        if (value == '.') {
            return '_';
        }
        if (value == '_' || value == '-' || isAsciiLetterOrDigit(value)) {
            return value;
        }
        return '_';
    }

    private boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private String stripBoundaryUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }
}
```

- [ ] **Step 4: Run mapper tests**

Run:

```bash
mvn -q -Dtest=OtelObservationMapperTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapper.java src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationMapperTest.java
git commit -m "feat: map ai observations to otel attributes"
```

---

### Task 3: Add OTel Publisher

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisher.java`
- Test: `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisherTest.java`

- [ ] **Step 1: Write publisher tests**

Create `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisherTest.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class OtelAiObservationPublisherTest {

    @Test
    void publishDoesNotThrowForNullEvent() {
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(
                OpenTelemetry.noop().getTracer("test"),
                new OtelObservationMapper()
        );

        assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
    }

    @Test
    void publishDoesNotThrowForNormalOrFailedEvent() {
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(
                OpenTelemetry.noop().getTracer("test"),
                new OtelObservationMapper()
        );

        assertThatCode(() -> publisher.publish(AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "knowledge retrieval",
                "success",
                Map.of("model", "glm-4", "docCount", "3")
        ))).doesNotThrowAnyException();

        assertThatCode(() -> publisher.publish(AiObservationEvent.ragNode(
                "trace-1",
                "node-2",
                "generation",
                "answer generation",
                "failed",
                Map.of("errorType", "ERROR")
        ))).doesNotThrowAnyException();
    }

    @Test
    void publishSwallowsMapperFailures() {
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(
                OpenTelemetry.noop().getTracer("test"),
                new OtelObservationMapper() {
                    @Override
                    public Map<String, String> toAttributes(AiObservationEvent event) {
                        throw new IllegalStateException("mapper_failed");
                    }
                }
        );

        assertThatCode(() -> publisher.publish(AiObservationEvent.ragNode(
                "trace-1", "node-1", "retrieval", "name", "success", Map.of()
        ))).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=OtelAiObservationPublisherTest test
```

Expected: compilation fails because publisher does not exist.

- [ ] **Step 3: Implement publisher**

Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisher.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class OtelAiObservationPublisher implements AiObservationPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OtelAiObservationPublisher.class);

    private final Tracer tracer;
    private final OtelObservationMapper mapper;

    public OtelAiObservationPublisher(Tracer tracer, OtelObservationMapper mapper) {
        this.tracer = tracer;
        this.mapper = mapper;
    }

    @Override
    public void publish(AiObservationEvent event) {
        if (event == null || tracer == null || mapper == null) {
            return;
        }
        try {
            Span span = tracer.spanBuilder(mapper.spanName(event)).startSpan();
            try {
                for (Map.Entry<String, String> entry : mapper.toAttributes(event).entrySet()) {
                    span.setAttribute(AttributeKey.stringKey(entry.getKey()), entry.getValue());
                }
                if ("failed".equalsIgnoreCase(event.status()) || "error".equalsIgnoreCase(event.status())) {
                    span.setStatus(StatusCode.ERROR);
                } else {
                    span.setStatus(StatusCode.OK);
                }
            } finally {
                span.end();
            }
        } catch (RuntimeException ex) {
            logger.debug("Failed to publish AI observation to OpenTelemetry: type={}, status={}",
                    event.eventType(), event.status());
        }
    }
}
```

- [ ] **Step 4: Run publisher tests**

Run:

```bash
mvn -q -Dtest=OtelAiObservationPublisherTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisher.java src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelAiObservationPublisherTest.java
git commit -m "feat: publish ai observations as otel spans"
```

---

### Task 4: Add Spring Configuration and Context Tests

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfig.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfigTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/observability/AiObservationPublisherConfigTest.java`

- [ ] **Step 1: Write config tests**

Create `src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfigTest.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OtelObservationConfigTest {

    @Test
    void usesNoopWhenExternalObservationIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtelObservationConfig.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(NoopAiObservationPublisher.class);
                });
    }

    @Test
    void usesNoopWhenEnabledButMissingKeys() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtelObservationConfig.class))
                .withPropertyValues(
                        "app.observability.external.enabled=true",
                        "app.observability.external.endpoint=https://us.cloud.langfuse.com/api/public/otel"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(NoopAiObservationPublisher.class);
                });
    }

    @Test
    void createsOtelPublisherWhenConfigurationIsComplete() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtelObservationConfig.class))
                .withPropertyValues(
                        "app.observability.external.enabled=true",
                        "app.observability.external.provider=langfuse-otel",
                        "app.observability.external.endpoint=https://us.cloud.langfuse.com/api/public/otel",
                        "app.observability.external.public-key=pk-test",
                        "app.observability.external.secret-key=sk-test",
                        "app.observability.external.service-name=study-agent-test"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(OtelAiObservationPublisher.class);
                });
    }

    @Test
    void registersOtelAutoConfigurationBeforeNoopFallbackAutoConfiguration() {
        List<String> imports = autoConfigurationImports();

        assertThat(imports).contains(OtelObservationConfig.class.getName());
        assertThat(imports).contains(AiObservationPublisherConfig.class.getName());
        assertThat(imports.indexOf(OtelObservationConfig.class.getName()))
                .isLessThan(imports.indexOf(AiObservationPublisherConfig.class.getName()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=OtelObservationConfigTest test
```

Expected: compilation fails because config does not exist.

- [ ] **Step 3: Implement config**

Create `src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfig.java`:

```java
package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
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

@AutoConfiguration
@EnableConfigurationProperties(OtelObservationProperties.class)
public class OtelObservationConfig {

    private static final Logger logger = LoggerFactory.getLogger(OtelObservationConfig.class);
    private static final String INSTRUMENTATION_NAME = "study-agent-ai-observability";

    @Bean
    @ConditionalOnMissingBean(AiObservationPublisher.class)
    public AiObservationPublisher otelAiObservationPublisher(OtelObservationProperties properties) {
        if (properties == null || !properties.isConfigured()) {
            return new NoopAiObservationPublisher();
        }
        try {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(properties.getEndpoint())
                    .addHeader("Authorization", properties.basicAuthorizationHeader())
                    .build();
            Resource resource = Resource.getDefault().merge(Resource.create(
                    io.opentelemetry.api.common.Attributes.of(
                            AttributeKey.stringKey("service.name"), properties.getServiceName()
                    )
            ));
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            return new OtelAiObservationPublisher(
                    openTelemetry.getTracer(INSTRUMENTATION_NAME),
                    new OtelObservationMapper()
            );
        } catch (RuntimeException ex) {
            logger.warn("OpenTelemetry observation publisher initialization failed; falling back to noop: {}", ex.getMessage());
            return new NoopAiObservationPublisher();
        }
    }
}
```

- [ ] **Step 4: Register auto-configuration import**

Modify `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` so `OtelObservationConfig` is registered before the existing Noop fallback auto-configuration:

```text
io.github.imzmq.interview.observability.infrastructure.otel.OtelObservationConfig
io.github.imzmq.interview.config.observability.AiObservationPublisherConfig
```

The order matters because `OtelObservationConfig` and `AiObservationPublisherConfig` both provide an `AiObservationPublisher` with `@ConditionalOnMissingBean`; if the Noop fallback is imported first, the real OTel publisher cannot be created during normal Spring Boot startup.

Lifecycle note from code review: the configured publisher must own the `SdkTracerProvider` it creates. `OtelAiObservationPublisher` implements `AutoCloseable`; on Spring context shutdown, Spring's inferred destroy method calls `close()`, which force-flushes pending spans and then shuts down the provider/exporter chain. Disabled or incomplete configuration still returns `NoopAiObservationPublisher`, which has no inferred destroy method and must not fail context shutdown.

- [ ] **Step 5: Add integration expectation to existing config test**

Modify `src/test/java/io/github/imzmq/interview/config/observability/AiObservationPublisherConfigTest.java` only if needed to include `OtelObservationConfig` in a new test. Add imports and this test:

```java
    @Test
    void otelPublisherCanOverrideNoopFallbackWhenConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        io.github.imzmq.interview.observability.infrastructure.otel.OtelObservationConfig.class,
                        AiObservationPublisherConfig.class
                ))
                .withPropertyValues(
                        "app.observability.external.enabled=true",
                        "app.observability.external.provider=langfuse-otel",
                        "app.observability.external.endpoint=https://us.cloud.langfuse.com/api/public/otel",
                        "app.observability.external.public-key=pk-test",
                        "app.observability.external.secret-key=sk-test"
                )
                .run(context -> assertThat(context).getBean(AiObservationPublisher.class)
                        .isInstanceOf(io.github.imzmq.interview.observability.infrastructure.otel.OtelAiObservationPublisher.class));
    }
```

- [ ] **Step 6: Run config tests**

Run:

```bash
mvn -q -Dtest=OtelObservationConfigTest,AiObservationPublisherConfigTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfig.java src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports src/test/java/io/github/imzmq/interview/observability/infrastructure/otel/OtelObservationConfigTest.java src/test/java/io/github/imzmq/interview/config/observability/AiObservationPublisherConfigTest.java
git commit -m "feat: configure langfuse otel observation publisher"
```

---

### Task 5: Documentation and Verification

**Files:**
- Modify: `docs/development/observability-guidelines.md`
- Review: `AGENTS.md`

- [ ] **Step 1: Update observability guide**

Add this section to `docs/development/observability-guidelines.md` after “后续 Langfuse adapter 规则”:

```markdown
## Langfuse via OpenTelemetry 配置

第一版 Langfuse 接入通过 OpenTelemetry OTLP/HTTP exporter 完成，不直接使用 Langfuse Java Client。默认关闭：

```yaml
app:
  observability:
    external:
      enabled: false
      provider: langfuse-otel
      endpoint: ${LANGFUSE_OTEL_ENDPOINT:}
      public-key: ${LANGFUSE_PUBLIC_KEY:}
      secret-key: ${LANGFUSE_SECRET_KEY:}
      service-name: study-agent
      export-prompts: false
      export-completions: false
```

常用 endpoint：

```text
EU Cloud:    https://cloud.langfuse.com/api/public/otel
US Cloud:    https://us.cloud.langfuse.com/api/public/otel
Self-hosted: https://<your-langfuse-host>/api/public/otel
```

认证使用 `Authorization: Basic base64(public-key:secret-key)`，由配置类生成，不要把 secret 写入日志、trace attributes 或文档示例。

当前只导出 `AiObservationEvent` 的安全 attributes。`export-prompts` 和 `export-completions` 预留但不生效，不得默认外传 prompt/completion 原文。
```

- [ ] **Step 2: Remove local AGENTS.md noise if present**

Run:

```bash
git diff -- AGENTS.md
```

If the diff is only `<claude-mem-context>`, remove it:

```bash
python3 - <<'PY'
from pathlib import Path
path = Path('AGENTS.md')
text = path.read_text()
start = text.find('\n\n<claude-mem-context>')
if start != -1:
    path.write_text(text[:start].rstrip() + '\n')
PY
```

- [ ] **Step 3: Run focused tests**

Run:

```bash
mvn -q -Dtest=OtelObservationPropertiesTest,OtelObservationMapperTest,OtelAiObservationPublisherTest,OtelObservationConfigTest,AiObservationPublisherConfigTest test
```

Expected: pass.

- [ ] **Step 4: Run compile and diff checks**

Run:

```bash
mvn -q -DskipTests compile
git diff --check
git status --short
```

Expected: compile passes; diff check has no output; status only shows intended docs change before commit.

- [ ] **Step 5: Run full test suite**

Run:

```bash
mvn -q test
```

Expected: full suite passes. If it fails with known Mockito inline / ByteBuddy self-attach errors, capture the error and state that focused tests passed while full suite remains environment-blocked.

- [ ] **Step 6: Commit docs**

```bash
git add docs/development/observability-guidelines.md
git commit -m "docs: document langfuse otel configuration"
```

- [ ] **Step 7: Final summary commands**

Run:

```bash
git log --oneline --decorate -8
git status -sb
```

Expected: working tree is clean and branch is ahead of `origin/main` by Langfuse OTEL design/implementation commits.

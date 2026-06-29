# Startup Lightweight Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Study-Agent startup lighter and more explicit by moving root config, defaulting optional connectors off, gating startup-heavy background jobs, and printing startup diagnostics.

**Architecture:** Keep the existing Spring Boot application entry. Add a small `bootstrap` read-only diagnostics component and property class. Use Spring conditional properties on heavy startup/scheduled components so business code remains unchanged.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring configuration properties, JUnit 5, AssertJ, ApplicationContextRunner.

---

## File Structure

- Move `src/main/java/io/github/imzmq/interview/ModelSelectionConfig.java` to `src/main/java/io/github/imzmq/interview/config/model/ModelSelectionConfig.java`.
- Modify `src/main/resources/application.yml` — default IM WS off and add startup/probe/dream switches.
- Modify `src/main/resources/application-local-lite.yml` — keep lightweight overrides explicit.
- Modify `src/main/java/io/github/imzmq/interview/modelrouting/registry/DynamicModelPreheater.java` — conditional on `app.startup.model-preheat-enabled=true`.
- Modify `src/main/java/io/github/imzmq/interview/modelrouting/probe/ModelHealthProbeScheduler.java` — conditional on `app.model-routing.probe.enabled=true`.
- Modify `src/main/java/io/github/imzmq/interview/chat/application/AutoDreamService.java` — conditional on `app.dream.enabled=true`.
- Create `src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsProperties.java` — typed property snapshot for startup diagnostics.
- Create `src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnostics.java` — logs ready-time module state.
- Create tests under `src/test/java/io/github/imzmq/interview/bootstrap/`.

---

### Task 1: Move ModelSelectionConfig out of the root package

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/ModelSelectionConfig.java`
- Create: `src/main/java/io/github/imzmq/interview/config/model/ModelSelectionConfig.java`

- [ ] **Step 1: Move the file and update package**

Create `src/main/java/io/github/imzmq/interview/config/model/ModelSelectionConfig.java` with the same bean methods and this package declaration:

```java
package io.github.imzmq.interview.config.model;
```

Keep both bean methods unchanged:

```java
@Bean
@Primary
public ChatModel primaryChatModel(@Qualifier("openAiChatModel") ChatModel chatModel) {
    return chatModel;
}

@Bean
@Primary
public EmbeddingModel primaryEmbeddingModel(@Qualifier("zhiPuAiEmbeddingModel") EmbeddingModel embeddingModel) {
    return embeddingModel;
}
```

Delete the old root file after the new file exists.

- [ ] **Step 2: Compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: compile succeeds.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/config/model/ModelSelectionConfig.java
git rm src/main/java/io/github/imzmq/interview/ModelSelectionConfig.java
git commit -m "refactor: move model selection config out of root package"
```

---

### Task 2: Add lightweight startup defaults and conditional gates

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local-lite.yml`
- Modify: `src/main/java/io/github/imzmq/interview/modelrouting/registry/DynamicModelPreheater.java`
- Modify: `src/main/java/io/github/imzmq/interview/modelrouting/probe/ModelHealthProbeScheduler.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/AutoDreamService.java`
- Test: `src/test/java/io/github/imzmq/interview/bootstrap/StartupConditionDefaultsTest.java`

- [ ] **Step 1: Add failing conditional defaults tests**

Create `src/test/java/io/github/imzmq/interview/bootstrap/StartupConditionDefaultsTest.java`:

```java
package io.github.imzmq.interview.bootstrap;

import io.github.imzmq.interview.chat.application.AutoDreamService;
import io.github.imzmq.interview.im.application.config.FeishuProperties;
import io.github.imzmq.interview.im.application.config.QqProperties;
import io.github.imzmq.interview.modelrouting.probe.ModelHealthProbeScheduler;
import io.github.imzmq.interview.modelrouting.registry.DynamicModelPreheater;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StartupConditionDefaultsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void imWebsocketPropertiesDefaultToDisabled() {
        contextRunner
                .withUserConfiguration(QqProperties.class, FeishuProperties.class)
                .run(context -> {
                    assertThat(context.getBean(QqProperties.class).isUseWs()).isFalse();
                    assertThat(context.getBean(FeishuProperties.class).isUseWs()).isFalse();
                });
    }

    @Test
    void heavyStartupComponentsAreDisabledByDefault() {
        contextRunner
                .withUserConfiguration(DynamicModelPreheater.class, ModelHealthProbeScheduler.class, AutoDreamService.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DynamicModelPreheater.class);
                    assertThat(context).doesNotHaveBean(ModelHealthProbeScheduler.class);
                    assertThat(context).doesNotHaveBean(AutoDreamService.class);
                });
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -q -Dtest=StartupConditionDefaultsTest test
```

Expected: fails because heavy components are still registered or because the conditional annotations are missing.

- [ ] **Step 3: Update application.yml defaults**

In `src/main/resources/application.yml`, add under `app:`:

```yaml
  startup:
    model-preheat-enabled: ${APP_STARTUP_MODEL_PREHEAT_ENABLED:false}
```

Add under existing `app.model-routing:`:

```yaml
    probe:
      enabled: ${APP_MODEL_ROUTING_PROBE_ENABLED:false}
```

Update existing `app.dream:` block to include:

```yaml
    enabled: ${APP_DREAM_ENABLED:false}
```

Update `im:` defaults:

```yaml
im:
  qq:
    use-ws: ${IM_QQ_USE_WS:false}
  feishu:
    use-ws: ${IM_FEISHU_USE_WS:false}
```

- [ ] **Step 4: Keep local-lite explicitly lightweight**

In `src/main/resources/application-local-lite.yml`, add explicit lightweight overrides:

```yaml
app:
  startup:
    model-preheat-enabled: false
  model-routing:
    probe:
      enabled: false
  dream:
    enabled: false
```

Preserve existing local-lite values for RAG warmup and IM WS.

- [ ] **Step 5: Add conditional annotations**

Add imports and annotations:

`DynamicModelPreheater.java`:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.startup.model-preheat-enabled", havingValue = "true")
public class DynamicModelPreheater {
```

`ModelHealthProbeScheduler.java`:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.model-routing.probe.enabled", havingValue = "true")
public class ModelHealthProbeScheduler {
```

`AutoDreamService.java`:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "app.dream.enabled", havingValue = "true")
public class AutoDreamService {
```

- [ ] **Step 6: Run focused test**

Run:

```bash
mvn -q -Dtest=StartupConditionDefaultsTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yml src/main/resources/application-local-lite.yml \
  src/main/java/io/github/imzmq/interview/modelrouting/registry/DynamicModelPreheater.java \
  src/main/java/io/github/imzmq/interview/modelrouting/probe/ModelHealthProbeScheduler.java \
  src/main/java/io/github/imzmq/interview/chat/application/AutoDreamService.java \
  src/test/java/io/github/imzmq/interview/bootstrap/StartupConditionDefaultsTest.java
git commit -m "feat: gate heavy startup components"
```

---

### Task 3: Add startup diagnostics

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsProperties.java`
- Create: `src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnostics.java`
- Test: `src/test/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsTest.java`

- [ ] **Step 1: Add failing diagnostics tests**

Create `src/test/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsTest.java`:

```java
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
                        "app.observability.external.provider=langfuse-otel",
                        "app.observability.external.endpoint=https://cloud.langfuse.com/api/public/otel",
                        "app.observability.external.public-key=pk-lf-test",
                        "app.observability.external.secret-key=sk-lf-test")
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
                    assertThat(snapshot.externalObservabilityEnabled()).isTrue();
                    assertThat(snapshot.externalObservabilityProvider()).isEqualTo("langfuse-otel");
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
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -q -Dtest=StartupDiagnosticsTest test
```

Expected: compilation fails because diagnostics classes do not exist.

- [ ] **Step 3: Implement properties**

Create `src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsProperties.java`:

```java
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
    private static final String EXTERNAL_OBSERVABILITY_PROVIDER = "langfuse-otel";

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
        this.externalObservabilityProvider = trimOrDefault(externalObservabilityProvider, EXTERNAL_OBSERVABILITY_PROVIDER);
        this.externalObservabilityEndpoint = trimOrDefault(externalObservabilityEndpoint, "");
        this.externalObservabilityPublicKey = trimOrDefault(externalObservabilityPublicKey, "");
        this.externalObservabilitySecretKey = trimOrDefault(externalObservabilitySecretKey, "");
    }

    public boolean isRagWarmupEnabled() { return ragWarmupEnabled; }
    public boolean isModelPreheatEnabled() { return modelPreheatEnabled; }
    public boolean isModelProbeEnabled() { return modelProbeEnabled; }
    public boolean isDreamEnabled() { return dreamEnabled; }
    public boolean isQqWsEnabled() { return qqWsEnabled; }
    public boolean isFeishuWsEnabled() { return feishuWsEnabled; }
    public boolean isExternalObservabilityEnabled() { return isExternalObservabilityConfigured(); }
    public String getExternalObservabilityProvider() { return externalObservabilityProvider; }

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
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 4: Implement diagnostics logger**

Create `src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnostics.java`:

```java
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
```

- [ ] **Step 5: Run focused test**

Run:

```bash
mvn -q -Dtest=StartupDiagnosticsTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsProperties.java \
  src/main/java/io/github/imzmq/interview/bootstrap/StartupDiagnostics.java \
  src/test/java/io/github/imzmq/interview/bootstrap/StartupDiagnosticsTest.java
git commit -m "feat: add startup diagnostics logging"
```

---

### Task 4: Verification and documentation touch-up

**Files:**
- Modify if needed: `docs/development/local-startup.md` or existing startup docs discovered in repo.

- [ ] **Step 1: Locate startup docs**

Run:

```bash
find docs -maxdepth 3 -type f | sort | grep -E 'startup|local|dev|启动' || true
```

Expected: identify the local startup document if present.

- [ ] **Step 2: Update docs if a startup guide exists**

If a startup guide exists, add a short section:

```markdown
## 轻量启动开关

默认本地启动不会自动连接 QQ/飞书 WebSocket，也不会执行模型预热、模型健康探测或自动记忆整理。需要开启时使用环境变量：

```bash
IM_FEISHU_USE_WS=true IM_QQ_USE_WS=true APP_STARTUP_MODEL_PREHEAT_ENABLED=true APP_MODEL_ROUTING_PROBE_ENABLED=true APP_DREAM_ENABLED=true bash scripts/dev-start.sh
```

启动完成后，后端日志会输出 `Study-Agent startup`，用于确认当前 profile 和关键模块启停状态。
```

If no startup guide exists, skip doc changes and state that no suitable doc file was found.

- [ ] **Step 3: Run focused tests**

Run:

```bash
mvn -q -Dtest=StartupConditionDefaultsTest,StartupDiagnosticsTest test
```

Expected: pass.

- [ ] **Step 4: Run compile and diff checks**

Run:

```bash
mvn -q -DskipTests compile
git diff --check
git status --short
```

Expected: compile passes; diff check has no output; status only shows intended changes.

- [ ] **Step 5: Run full tests**

Run:

```bash
mvn -q test
```

Expected: full suite passes. If it fails with known Mockito inline / ByteBuddy self-attach errors, capture the error and state focused tests and compile passed while full suite remains environment-blocked.

- [ ] **Step 6: Commit verification/docs if changed**

If docs changed:

```bash
git add -f docs/development/local-startup.md
git commit -m "docs: document lightweight startup switches"
```

If no docs changed, do not create an empty commit.

- [ ] **Step 7: Final summary commands**

Run:

```bash
git log --oneline --decorate -8
git status -sb
```

Expected: working tree clean except recurring local `AGENTS.md` noise; remove `<claude-mem-context>` noise before final report or commit.

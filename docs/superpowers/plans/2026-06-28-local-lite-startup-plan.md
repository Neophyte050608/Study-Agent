# Local Lite Startup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `local-lite` Spring profile that skips optional startup-heavy components while preserving default full startup behavior.

**Architecture:** Keep the main application and default configuration unchanged for full mode. Add profile-specific configuration to disable optional startup work, and add conditional bean loading for QQ WebSocket and Milvus image collection initialization so disabled features do not create beans.

**Tech Stack:** Java 21, Spring Boot 3.3.6, Spring conditional configuration, Maven, JUnit 5, ApplicationContextRunner.

---

## File Structure

- Create: `src/main/resources/application-local-lite.yml` — lightweight local development profile overrides.
- Modify: `src/main/java/io/github/imzmq/interview/im/application/service/QqWsService.java` — add conditional bean loading for QQ WebSocket.
- Modify: `src/main/java/io/github/imzmq/interview/config/media/ImageVectorStoreConfig.java` — add conditional bean loading for Milvus image collection initialization.
- Create: `src/test/java/io/github/imzmq/interview/config/startup/LocalLiteConditionalConfigTest.java` — verifies disabled feature flags prevent heavy beans from loading.

## Task 1: Add Conditional Loading Tests First

**Files:**
- Create: `src/test/java/io/github/imzmq/interview/config/startup/LocalLiteConditionalConfigTest.java`

- [ ] **Step 1: Write failing conditional tests**

Create `LocalLiteConditionalConfigTest` with the following content:

```java
package io.github.imzmq.interview.config.startup;

import io.github.imzmq.interview.config.media.ImageVectorStoreConfig;
import io.github.imzmq.interview.im.application.config.QqProperties;
import io.github.imzmq.interview.im.application.service.ImWebhookService;
import io.github.imzmq.interview.im.application.service.QqAuthTokenProvider;
import io.github.imzmq.interview.im.application.service.QqEventParser;
import io.github.imzmq.interview.im.application.service.QqWsService;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalLiteConditionalConfigTest {

    @Test
    void shouldNotCreateQqWsServiceWhenQqWebSocketDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("im.qq.use-ws=false")
                .withUserConfiguration(QqWsService.class)
                .withBean(QqProperties.class, QqProperties::new)
                .withBean(QqEventParser.class, () -> mock(QqEventParser.class))
                .withBean(ImWebhookService.class, () -> mock(ImWebhookService.class))
                .withBean(QqAuthTokenProvider.class, () -> mock(QqAuthTokenProvider.class))
                .run(context -> assertThat(context).doesNotHaveBean(QqWsService.class));
    }

    @Test
    void shouldCreateQqWsServiceWhenQqWebSocketEnabled() {
        new ApplicationContextRunner()
                .withPropertyValues("im.qq.use-ws=true")
                .withUserConfiguration(QqWsService.class)
                .withBean(QqProperties.class, QqProperties::new)
                .withBean(QqEventParser.class, () -> mock(QqEventParser.class))
                .withBean(ImWebhookService.class, () -> mock(ImWebhookService.class))
                .withBean(QqAuthTokenProvider.class, () -> mock(QqAuthTokenProvider.class))
                .run(context -> assertThat(context).hasSingleBean(QqWsService.class));
    }

    @Test
    void shouldNotCreateImageVectorStoreConfigWhenImageStoreDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("app.multimodal.image-store.enabled=false")
                .withUserConfiguration(ImageVectorStoreConfig.class)
                .withBean(MilvusServiceClient.class, () -> mock(MilvusServiceClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(ImageVectorStoreConfig.class));
    }

    @Test
    void shouldCreateImageVectorStoreConfigWhenImageStoreEnabled() {
        new ApplicationContextRunner()
                .withPropertyValues("app.multimodal.image-store.enabled=true")
                .withUserConfiguration(ImageVectorStoreConfig.class)
                .withBean(MilvusServiceClient.class, () -> mock(MilvusServiceClient.class))
                .run(context -> assertThat(context).hasSingleBean(ImageVectorStoreConfig.class));
    }
}
```

- [ ] **Step 2: Verify tests fail for missing conditional behavior**

Run:

```bash
mvn -q -Dtest=LocalLiteConditionalConfigTest test
```

Expected: tests compile but fail because `QqWsService` and `ImageVectorStoreConfig` are still created even when their disabling properties are false. If Mockito inline Byte Buddy self-attach fails in the sandbox, rerun the same command outside the sandbox and record that the failure mode is environmental.

## Task 2: Add Conditional Annotations

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/im/application/service/QqWsService.java`
- Modify: `src/main/java/io/github/imzmq/interview/config/media/ImageVectorStoreConfig.java`

- [ ] **Step 1: Add conditional import and annotation to `QqWsService`**

Add import:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

Change the class annotations to:

```java
@Service
@ConditionalOnProperty(prefix = "im.qq", name = "use-ws", havingValue = "true")
public class QqWsService {
```

Keep the existing `init()` guard:

```java
if (!qqProperties.isUseWs()) {
    log.info("【QQ WS】长连接模式未开启，跳过初始化。");
    return;
}
```

- [ ] **Step 2: Add conditional import and annotation to `ImageVectorStoreConfig`**

Add import:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

Change the class annotations to:

```java
@Configuration
@ConditionalOnProperty(prefix = "app.multimodal.image-store", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ImageVectorStoreConfig {
```

- [ ] **Step 3: Verify focused tests pass**

Run:

```bash
mvn -q -Dtest=LocalLiteConditionalConfigTest test
```

Expected: all tests pass. If Mockito inline Byte Buddy self-attach fails in the sandbox, rerun outside the sandbox.

## Task 3: Add `local-lite` Profile Configuration

**Files:**
- Create: `src/main/resources/application-local-lite.yml`

- [ ] **Step 1: Create profile override file**

Create `application-local-lite.yml` with:

```yaml
# Lightweight local development profile.
# Use with: mvn spring-boot:run -Dspring-boot.run.profiles=local-lite

spring:
  datasource:
    hikari:
      minimum-idle: 0
      maximum-pool-size: 5
      connection-timeout: 5000
  ai:
    vectorstore:
      milvus:
        initialize-schema: false

logging:
  level:
    org.springframework.ai: INFO

app:
  knowledge:
    retrieval:
      warmup-enabled: false
  multimodal:
    image-store:
      enabled: false

im:
  qq:
    use-ws: false
  feishu:
    use-ws: false
```

- [ ] **Step 2: Verify resource is packaged**

Run:

```bash
mvn -q -DskipTests test-compile
```

Expected: exit 0.

## Task 4: Final Verification and Commit

**Files:**
- All modified source, test, and resource files.

- [ ] **Step 1: Run final gates**

Run:

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q -Dtest=LocalLiteConditionalConfigTest test
mvn -q verify -DskipTests
git diff --check
```

Expected: all commands exit 0. If `LocalLiteConditionalConfigTest` fails only due Mockito inline Byte Buddy self-attach in the sandbox, rerun it outside the sandbox.

- [ ] **Step 2: Self-review diff**

Run:

```bash
git diff --stat
git diff -- src/main/java/io/github/imzmq/interview/im/application/service/QqWsService.java
git diff -- src/main/java/io/github/imzmq/interview/config/media/ImageVectorStoreConfig.java
git diff -- src/main/resources/application-local-lite.yml
```

Confirm:

```text
- Default full startup behavior remains unchanged when local-lite is not active.
- QQ WebSocket bean is skipped when im.qq.use-ws=false.
- Image Milvus collection initializer is skipped when app.multimodal.image-store.enabled=false.
- Retrieval warmup is disabled in local-lite via existing property.
- No Maven dependencies or module structure changed.
```

- [ ] **Step 3: Commit implementation**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/im/application/service/QqWsService.java \
        src/main/java/io/github/imzmq/interview/config/media/ImageVectorStoreConfig.java \
        src/main/resources/application-local-lite.yml \
        src/test/java/io/github/imzmq/interview/config/startup/LocalLiteConditionalConfigTest.java

git commit -m "feat: 添加轻量本地启动模式"
```

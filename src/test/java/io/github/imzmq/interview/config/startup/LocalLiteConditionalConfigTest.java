package io.github.imzmq.interview.config.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.chat.application.memory.ChatMemoryProjectionWorker;
import io.github.imzmq.interview.chat.infrastructure.milvus.ChatMemoryMilvusSchemaInitializer;
import io.github.imzmq.interview.config.media.ImageVectorStoreConfig;
import io.github.imzmq.interview.media.application.ImageEmbeddingService;
import io.github.imzmq.interview.media.application.ImageIndexService;
import io.github.imzmq.interview.media.infrastructure.persistence.ImageMetadataMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import io.github.imzmq.interview.im.application.config.QqProperties;
import io.github.imzmq.interview.im.application.service.ImWebhookService;
import io.github.imzmq.interview.im.application.service.QqAuthTokenProvider;
import io.github.imzmq.interview.im.application.service.QqEventParser;
import io.github.imzmq.interview.im.application.service.QqWsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLiteConditionalConfigTest {

    @Test
    void shouldNotCreateQqWsServiceWhenQqWebSocketDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("im.qq.use-ws=false")
                .withUserConfiguration(QqWsService.class)
                .withBean(QqProperties.class, QqProperties::new)
                .withBean(QqEventParser.class, () -> new QqEventParser(new QqProperties()))
                .withBean(ImWebhookService.class, LocalLiteConditionalConfigTest::noopImWebhookService)
                .withBean(QqAuthTokenProvider.class, LocalLiteConditionalConfigTest::noopQqAuthTokenProvider)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> assertThat(context).doesNotHaveBean(QqWsService.class));
    }

    @Test
    void shouldNotCreateMemoryProjectionComponentsWhenMemoryDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("app.chat.memory.enabled=false")
                .withUserConfiguration(
                        ChatMemoryProjectionWorker.class,
                        ChatMemoryMilvusSchemaInitializer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatMemoryProjectionWorker.class);
                    assertThat(context).doesNotHaveBean(ChatMemoryMilvusSchemaInitializer.class);
                });
    }

    @Test
    void shouldNotCreateImageVectorStoreConfigWhenImageStoreDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("app.multimodal.image-store.enabled=false")
                .withUserConfiguration(ImageVectorStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageVectorStoreConfig.class);
                });
    }

    @Test
    void shouldCreateDisabledImageIndexServiceWithoutMilvusClient() {
        new ApplicationContextRunner()
                .withPropertyValues("app.multimodal.image-store.enabled=false")
                .withUserConfiguration(ImageIndexService.class)
                .withBean(ImageMetadataMapper.class, LocalLiteConditionalConfigTest::noopImageMetadataMapper)
                .withBean(ImageEmbeddingService.class, LocalLiteConditionalConfigTest::noopImageEmbeddingService)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ImageIndexService service = context.getBean(ImageIndexService.class);
                    assertThat(service.search("java", 3, false)).isEmpty();
                });
    }

    @Test
    void localLiteShouldDefaultDynamicPlanningShadowToDisabled() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local-lite")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("app.learning.plan-preview.dynamic-planning.shadow-enabled"))
                        .isEqualTo("false"));
    }

    @Test
    void localLiteShouldAllowDynamicPlanningShadowOverride() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withSystemProperties("APP_LEARNING_PLAN_DYNAMIC_SHADOW_ENABLED=true")
                .withPropertyValues("spring.profiles.active=local-lite")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("app.learning.plan-preview.dynamic-planning.shadow-enabled"))
                        .isEqualTo("true"));
    }


    private static ImageMetadataMapper noopImageMetadataMapper() {
        return (ImageMetadataMapper) java.lang.reflect.Proxy.newProxyInstance(
                ImageMetadataMapper.class.getClassLoader(),
                new Class<?>[]{ImageMetadataMapper.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static ImageEmbeddingService noopImageEmbeddingService() {
        EmbeddingModel embeddingModel = (EmbeddingModel) java.lang.reflect.Proxy.newProxyInstance(
                EmbeddingModel.class.getClassLoader(),
                new Class<?>[]{EmbeddingModel.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        return new ImageEmbeddingService(embeddingModel, "http://localhost:8200", 512, false);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0f;
        if (returnType == double.class) return 0.0d;
        if (returnType == char.class) return '\0';
        if (returnType == float[].class) return new float[0];
        return null;
    }

    private static ImWebhookService noopImWebhookService() {
        return new ImWebhookService(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static QqAuthTokenProvider noopQqAuthTokenProvider() {
        return new QqAuthTokenProvider(new QqProperties(), RestClient.builder());
    }
}

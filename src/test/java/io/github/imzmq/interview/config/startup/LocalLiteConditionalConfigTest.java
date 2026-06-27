package io.github.imzmq.interview.config.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.config.media.ImageVectorStoreConfig;
import io.github.imzmq.interview.im.application.config.QqProperties;
import io.github.imzmq.interview.im.application.service.ImWebhookService;
import io.github.imzmq.interview.im.application.service.QqAuthTokenProvider;
import io.github.imzmq.interview.im.application.service.QqEventParser;
import io.github.imzmq.interview.im.application.service.QqWsService;
import org.junit.jupiter.api.Test;
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
    void shouldNotCreateImageVectorStoreConfigWhenImageStoreDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("app.multimodal.image-store.enabled=false")
                .withUserConfiguration(ImageVectorStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageVectorStoreConfig.class);
                });
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

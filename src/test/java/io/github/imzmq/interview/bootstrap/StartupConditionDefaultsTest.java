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

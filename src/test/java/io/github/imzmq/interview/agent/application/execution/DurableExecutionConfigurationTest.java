package io.github.imzmq.interview.agent.application.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableExecutionConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DurableExecutionProperties.class, DurableExecutionWorker.class,
                    DurableExecutionService.class, ExecutionJson.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ExecutionTransactions.class, () -> mock(ExecutionTransactions.class))
            .withBean(DurableWorkflowRegistry.class, () -> mock(DurableWorkflowRegistry.class))
            .withBean(FixedDagExecutor.class, FixedDagExecutor::new);
    @Test void disabledByDefaultAndSubmissionIsExplicitlyRejected() {
        context.run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(DurableExecutionWorker.class);
            assertThatThrownBy(() -> c.getBean(DurableExecutionService.class).submit("u", "x", 1, "k", "{}"))
                    .hasMessage("EXECUTION_DISABLED");
        });
    }
    @Test void enabledAssemblyAndLimits() {
        context.withPropertyValues("app.agent.execution.enabled=true").run(c ->
                assertThat(c).hasNotFailed().hasSingleBean(DurableExecutionWorker.class));
        context.withPropertyValues("app.agent.execution.heartbeat-seconds=11").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("app.agent.execution.coordinators=3").run(c -> assertThat(c).hasFailed());
    }
}

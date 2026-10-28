package io.github.imzmq.interview.agent.infrastructure.persistence.execution;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentExecutionJdbcTransactionConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentExecutionJdbcTransactionConfig.class)
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    void createsDedicatedJdbcTransactionManagerForAgentExecutionPersistence() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(AgentExecutionJdbcTransactionConfig.DATA_SOURCE_TRANSACTION_MANAGER);
            assertThat(context.getBean(AgentExecutionJdbcTransactionConfig.DATA_SOURCE_TRANSACTION_MANAGER))
                    .isInstanceOf(DataSourceTransactionManager.class);
        });
    }
}

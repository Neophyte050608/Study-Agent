package io.github.imzmq.interview.agent.infrastructure.persistence.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class AgentExecutionJdbcTransactionConfig {
    public static final String DATA_SOURCE_TRANSACTION_MANAGER = "dataSourceTransactionManager";

    @Bean(DATA_SOURCE_TRANSACTION_MANAGER)
    @ConditionalOnMissingBean(name = DATA_SOURCE_TRANSACTION_MANAGER)
    public DataSourceTransactionManager dataSourceTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

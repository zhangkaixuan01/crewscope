package io.crewscope.server.config.application;

import io.crewscope.application.correlation.CorrelationQueryPort;
import io.crewscope.application.correlation.CorrelationQueryService;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.server.api.CorrelationCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the current-member Correlation query and its rotatable signed cursor. */
@Configuration(proxyBeanMethods = false)
public class CorrelationApplicationConfiguration {

    @Bean
    CorrelationQueryService correlationQueryService(
            WorkItemAccessPolicy accessPolicy,
            CorrelationQueryPort queries,
            TransactionExecutor transactions) {
        return new CorrelationQueryService(accessPolicy, queries, transactions);
    }

    @Bean
    @ConditionalOnBean(TeamActivityCursorKeyRing.class)
    CorrelationCursorCodec correlationCursorCodec(TeamActivityCursorKeyRing keys) {
        return new CorrelationCursorCodec(keys);
    }
}

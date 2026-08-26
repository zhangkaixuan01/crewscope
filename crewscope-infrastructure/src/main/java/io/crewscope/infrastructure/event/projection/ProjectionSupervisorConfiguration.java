package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.application.observability.OperationalTelemetry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed conditional Spring assembly for projection background administration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProjectionSupervisorProperties.class)
public class ProjectionSupervisorConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "crewscope.projection.supervisor",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnBean({
        JdbcProjectionSupervisorStore.class,
        GenerationAwareProjectionRunnerFactory.class,
        JdbcProjectionEventHistoryStore.class,
        TimeProvider.class
    })
    ProjectionSupervisor projectionSupervisor(
            JdbcProjectionSupervisorStore store,
            ObjectProvider<GenerationAwareProjectionHandler> handlers,
            GenerationAwareProjectionRunnerFactory runnerFactory,
            JdbcProjectionEventHistoryStore historyStore,
            ProjectionSupervisorProperties properties,
            TimeProvider timeProvider,
            ObjectProvider<OperationalTelemetry> telemetry) {
        List<GenerationAwareProjectionHandler> registered = handlers.orderedStream().toList();
        return new ProjectionSupervisor(
                store,
                registered,
                runnerFactory,
                historyStore,
                properties,
                timeProvider,
                telemetry.getIfAvailable(OperationalTelemetry::noop));
    }

    @Bean
    @ConditionalOnBean(ProjectionSupervisor.class)
    ProjectionSupervisorScheduler projectionSupervisorScheduler(
            ProjectionSupervisor supervisor) {
        return new ProjectionSupervisorScheduler(supervisor);
    }
}

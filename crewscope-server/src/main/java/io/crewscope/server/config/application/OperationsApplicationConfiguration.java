package io.crewscope.server.config.application;

import io.crewscope.application.operations.OperationsHealthQueryPort;
import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsHealthThresholds;
import io.crewscope.application.operations.OperationsRecoveryRepository;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.projection.DefaultProjectionAdministration;
import io.crewscope.application.projection.ProjectionAdministration;
import io.crewscope.application.projection.ProjectionAdministrationRepository;
import io.crewscope.application.projection.ProjectionAdministrationService;
import io.crewscope.application.projection.ProjectionSnapshotVerifier;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conditional Spring composition for the M6 operations contracts and future I01/I02 adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperationsHealthProperties.class)
public class OperationsApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProjectionAdministration projectionAdministration() {
        return new DefaultProjectionAdministration();
    }

    @Bean
    @ConditionalOnMissingBean
    OperationsHealthThresholds operationsHealthThresholds(OperationsHealthProperties properties) {
        return properties.validatedThresholds();
    }

    @Bean
    @ConditionalOnMissingBean
    OperationsHealthService operationsHealthService(
            WorkItemAccessPolicy accessPolicy,
            ProjectionAdministration administration,
            OperationsHealthQueryPort queries,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            OperationsHealthThresholds thresholds) {
        return new OperationsHealthService(
                accessPolicy, administration, queries, transactions, timeProvider, thresholds);
    }

    @Bean
    @ConditionalOnMissingBean
    OperationsRecoveryService operationsRecoveryService(
            ProjectionAdministration administration,
            OperationsRecoveryRepository repository,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new OperationsRecoveryService(
                administration, repository, transactions, timeProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    ProjectionAdministrationService projectionAdministrationService(
            ProjectionAdministration administration,
            ProjectionAdministrationRepository repository,
            ProjectionSnapshotVerifier verifier,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ProjectionAdministrationService(
                administration, repository, verifier, transactions, timeProvider);
    }
}

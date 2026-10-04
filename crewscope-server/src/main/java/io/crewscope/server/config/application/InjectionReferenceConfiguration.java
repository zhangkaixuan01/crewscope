package io.crewscope.server.config.application;

import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.application.retrieval.InjectionReferenceRepository;
import io.crewscope.application.retrieval.InjectionReferenceService;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Server composition for the I02c evidence endpoints. The service always assembles —
 * sealed manifests are historical facts, so the reference read face, feedback and
 * claimed receipts are never gated on the injection or memory switches (I01c's
 * "closed gate keeps the read side" precedent). No properties, no restart-with-beans.
 */
@Configuration(proxyBeanMethods = false)
public class InjectionReferenceConfiguration {

    @Bean
    InjectionReferenceService injectionReferenceService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            InjectionManifestRepository manifests,
            InjectionReferenceRepository references,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        return new InjectionReferenceService(
                accessPolicy,
                tasks,
                executions,
                manifests,
                references,
                transactionExecutor,
                timeProvider);
    }
}

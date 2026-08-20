package io.crewscope.server.config.application;

import io.crewscope.application.coding.CodingArtifactAccessService;
import io.crewscope.application.coding.CodingArtifactContentPort;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit Spring assembly for member-authorized Coding Artifact content access. */
@Configuration(proxyBeanMethods = false)
public class CodingArtifactApplicationConfiguration {

    @Bean
    CodingArtifactAccessService codingArtifactAccessService(
            WorkItemAccessPolicy workItemAccessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            DiffArtifactRepository diffArtifactRepository,
            CommandEvidenceRepository commandEvidenceRepository,
            TestEvidenceRepository testEvidenceRepository,
            CodingArtifactContentPort contentPort,
            TransactionExecutor transactionExecutor) {
        return new CodingArtifactAccessService(
                workItemAccessPolicy,
                taskRepository,
                taskExecutionRepository,
                diffArtifactRepository,
                commandEvidenceRepository,
                testEvidenceRepository,
                contentPort,
                transactionExecutor);
    }
}

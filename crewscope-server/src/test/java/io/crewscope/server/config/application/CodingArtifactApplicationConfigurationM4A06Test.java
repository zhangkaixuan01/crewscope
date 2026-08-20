package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.coding.CodingArtifactAccessService;
import io.crewscope.application.coding.CodingArtifactContentPort;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Constructor-injected Spring assembly contract for M4-A06. */
class CodingArtifactApplicationConfigurationM4A06Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CodingArtifactApplicationConfiguration.class)
            .withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
            .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
            .withBean(TaskExecutionRepository.class, () -> mock(TaskExecutionRepository.class))
            .withBean(DiffArtifactRepository.class, () -> mock(DiffArtifactRepository.class))
            .withBean(CommandEvidenceRepository.class, () -> mock(CommandEvidenceRepository.class))
            .withBean(TestEvidenceRepository.class, () -> mock(TestEvidenceRepository.class))
            .withBean(CodingArtifactContentPort.class, () -> mock(CodingArtifactContentPort.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class));

    @Test
    void createsOneAccessServiceFromExplicitPorts() {
        runner.run(context -> context
                .assertThat()
                .hasNotFailed()
                .hasSingleBean(CodingArtifactAccessService.class));
    }
}

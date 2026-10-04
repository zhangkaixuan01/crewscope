package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.application.retrieval.InjectionReferenceRepository;
import io.crewscope.application.retrieval.InjectionReferenceService;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * M10-I02c assembly contract: the evidence service always assembles with no
 * properties of its own — sealed manifests are historical facts, so every switch
 * being off (injection, memory, retrieval) must leave the read face, feedback and
 * claimed receipts standing (I01c's "closed gate keeps the read side" precedent).
 */
class InjectionReferenceConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InjectionReferenceConfiguration.class)
            .withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
            .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
            .withBean(TaskExecutionRepository.class,
                    () -> mock(TaskExecutionRepository.class))
            .withBean(InjectionManifestRepository.class,
                    () -> mock(InjectionManifestRepository.class))
            .withBean(InjectionReferenceRepository.class,
                    () -> mock(InjectionReferenceRepository.class))
            .withBean(TransactionExecutor.class,
                    () -> new TransactionExecutor() {
                        @Override
                        public <T> T required(java.util.function.Supplier<T> operation) {
                            return operation.get();
                        }
                    })
            .withBean(TimeProvider.class, InjectionReferenceConfigurationTest::fixedClock);

    private static TimeProvider fixedClock() {
        return () -> UtcTimestamp.parse("2026-10-04T11:00:00Z");
    }

    @Test
    void theEvidenceServiceAlwaysAssembles() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(InjectionReferenceService.class));
    }

    @Test
    void everySwitchTurnedOffStillAssemblesTheEvidenceFace() {
        runner.withPropertyValues(
                        "crewscope.knowledge.injection.enabled=false",
                        "crewscope.memory.enabled=false",
                        "crewscope.knowledge.retrieval.enabled=false")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(InjectionReferenceService.class));
    }
}

package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies Worker-only M4-I05 Spring wiring and bounded inspection properties. */
class RepositoryInspectionConfigurationTest {

    @Test
    void createsInspectionFactoryForWorker() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.inspection.max-page-size=100",
                        "crewscope.coding.inspection.max-read-lines=300",
                        "crewscope.coding.inspection.max-tree-depth=5",
                        "crewscope.coding.inspection.max-backend-operations=40",
                        "crewscope.coding.inspection.max-pattern-length=128",
                        "crewscope.coding.inspection.max-result-bytes=32768")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(RepositoryInspectionProperties.class)
                        .hasSingleBean(RepositoryInspectionToolFactory.class));
    }

    @Test
    void pureServerProfileDoesNotCreateHostInspectionBeans() {
        runner()
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(RepositoryInspectionToolFactory.class));
    }

    @Test
    void rejectsUnboundedInspectionProperties() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.inspection.max-tree-depth=0",
                        "crewscope.coding.inspection.max-result-bytes=100")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(GitCommandExecutor.class, () -> org.mockito.Mockito.mock(GitCommandExecutor.class))
                .withUserConfiguration(RepositoryInspectionConfiguration.class);
    }
}

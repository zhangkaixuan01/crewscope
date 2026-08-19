package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies Worker-only M4-I04 Spring wiring and fail-closed Docker properties. */
class TaskExecutionSandboxConfigurationTest {

    @Test
    void createsTaskExecutionSandboxFactoryForWorker() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.sandbox.workspace-root=/workspace",
                        "crewscope.coding.sandbox.repository-mount=repository",
                        "crewscope.coding.sandbox.docker-command-timeout=10s",
                        "crewscope.coding.sandbox.pause-stop-timeout=1s",
                        "crewscope.coding.sandbox.pause-mode=STOP")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(TaskExecutionSandboxProperties.class)
                        .hasSingleBean(TaskExecutionSandboxFactory.class)
                        .hasSingleBean(DockerSandboxControl.class));
    }

    @Test
    void pureServerProfileDoesNotCreateHostDockerBeans() {
        runner()
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(TaskExecutionSandboxFactory.class)
                        .doesNotHaveBean(DockerSandboxControl.class));
    }

    @Test
    void rejectsArbitraryRepositoryMountAndNonPositiveTimeout() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.sandbox.repository-mount=../host",
                        "crewscope.coding.sandbox.docker-command-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(TaskExecutionSandboxConfiguration.class);
    }
}

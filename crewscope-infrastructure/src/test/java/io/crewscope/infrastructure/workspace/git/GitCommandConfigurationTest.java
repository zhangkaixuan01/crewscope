package io.crewscope.infrastructure.workspace.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies production Spring wiring and fail-closed Git process policy validation. */
class GitCommandConfigurationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void createsOneExecutorFromBoundProcessLimits() {
        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("configured-home"),
                        "crewscope.coding.git.timeout=12s",
                        "crewscope.coding.git.maximum-output-bytes=65536")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(GitCommandExecutor.class));
    }

    @Test
    void rejectsUnsafeProcessLimitsDuringBeanCreation() {
        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("invalid-home"),
                        "crewscope.coding.git.timeout=0s",
                        "crewscope.coding.git.maximum-output-bytes=100")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void backsOffWhenDeploymentProvidesAnExecutor() {
        GitCommandExecutor replacement = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("replacement-home"), Duration.ofSeconds(1), 4096));

        runner()
                .withBean(GitCommandExecutor.class, () -> replacement)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GitCommandExecutor.class);
                    assertThat(context.getBean(GitCommandExecutor.class)).isSameAs(replacement);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(GitCommandConfiguration.class);
    }
}

package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.infrastructure.workspace.git.GitCommandConfiguration;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies production Spring wiring and fail-closed managed repository configuration. */
class ManagedRepositoryConfigurationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void createsResolverAndPreflightFromBoundRootAndOwner() throws Exception {
        Path managedRoot = Files.createDirectory(temporaryDirectory.resolve("managed"));

        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("git-home"),
                        "crewscope.coding.repository.managed-root=" + managedRoot,
                        "crewscope.coding.repository.required-owner="
                                + Files.getOwner(managedRoot).getName())
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ManagedRepositoryResolver.class)
                        .hasSingleBean(BaselinePreflight.class)
                        .hasSingleBean(RepositoryBindingPreflightPort.class));
    }

    @Test
    void rejectsMissingManagedRootDuringBeanCreation() {
        Path missingRoot = temporaryDirectory.resolve("missing-managed-root");

        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("git-home"),
                        "crewscope.coding.repository.managed-root=" + missingRoot,
                        "crewscope.coding.repository.required-owner=worker")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(RepositoryPreflightException.class));
    }

    @Test
    void backsOffWhenDeploymentProvidesRepositoryBeans() throws Exception {
        Path managedRoot = Files.createDirectory(temporaryDirectory.resolve("replacement-root"));
        GitCommandExecutor gitCommands = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("replacement-home"), Duration.ofSeconds(1), 4096));
        ManagedRepositoryResolver resolver = new ManagedRepositoryResolver(
                managedRoot, Files.getOwner(managedRoot).getName(), gitCommands);
        BaselinePreflight preflight = new BaselinePreflight(resolver, gitCommands);

        runner()
                .withBean(GitCommandExecutor.class, () -> gitCommands)
                .withBean(ManagedRepositoryResolver.class, () -> resolver)
                .withBean(BaselinePreflight.class, () -> preflight)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ManagedRepositoryResolver.class)).isSameAs(resolver);
                    assertThat(context.getBean(BaselinePreflight.class)).isSameAs(preflight);
                });
    }

    @Test
    void pureServerProfileDoesNotRequireHostRepositoryAccess() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=server",
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("server-git-home"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ManagedRepositoryResolver.class);
                    assertThat(context).doesNotHaveBean(BaselinePreflight.class);
                    assertThat(context).doesNotHaveBean(RepositoryBindingPreflightPort.class);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        GitCommandConfiguration.class, ManagedRepositoryConfiguration.class);
    }
}

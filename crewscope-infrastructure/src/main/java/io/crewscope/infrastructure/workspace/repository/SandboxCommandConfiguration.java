package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.transaction.TransactionExecutor;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only Spring wiring for structured BuildProfile command execution and evidence. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@ConditionalOnBean({ArtifactStore.class, CommandEvidenceRepository.class})
@EnableConfigurationProperties({
    SandboxCommandProperties.class,
    CodingArtifactProperties.class
})
public class SandboxCommandConfiguration {

    @Bean
    @ConditionalOnMissingBean(SandboxCommandUsageRegistry.class)
    SandboxCommandUsageRegistry sandboxCommandUsageRegistry() {
        return new SandboxCommandUsageRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(BuildProfileCommandRunner.class)
    BuildProfileCommandRunner buildProfileCommandRunner() {
        return new BuildProfileCommandRunner(Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(CommandLogArtifactWriter.class)
    CommandLogArtifactWriter commandLogArtifactWriter(
            ArtifactStore artifactStore, CodingArtifactProperties properties) {
        return new CommandLogArtifactWriter(
                new CodingArtifactPublisher(artifactStore, properties));
    }

    @Bean
    @ConditionalOnMissingBean(TestReportArtifactWriter.class)
    TestReportArtifactWriter testReportArtifactWriter(
            ArtifactStore artifactStore, CodingArtifactProperties properties) {
        return new TestReportArtifactWriter(
                new CodingArtifactPublisher(artifactStore, properties));
    }

    @Bean
    @ConditionalOnMissingBean(CommandEvidenceWriter.class)
    CommandEvidenceWriter commandEvidenceWriter(
            CommandEvidenceRepository repository, CommandLogArtifactWriter commandLogs) {
        return new CommandEvidenceWriter(repository, commandLogs, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(TestEvidencePublisher.class)
    @ConditionalOnBean({TestEvidenceRepository.class, TestReportArtifactWriter.class})
    TestEvidencePublisher testEvidencePublisher(
            TestEvidenceRepository tests,
            TestReportArtifactWriter reports,
            CodingTaskTimelinePublisher timeline,
            TransactionExecutor transactions) {
        return new TestEvidencePublisher(
                tests, reports, Clock.systemUTC(), timeline, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(SandboxCommandToolFactory.class)
    SandboxCommandToolFactory sandboxCommandToolFactory(
            SandboxCommandProperties properties,
            CommandEvidenceRepository repository,
            SandboxCommandUsageRegistry usages,
            BuildProfileCommandRunner runner,
            CommandEvidenceWriter evidenceWriter) {
        return new SandboxCommandToolFactory(
                properties, repository, usages, runner, evidenceWriter);
    }
}

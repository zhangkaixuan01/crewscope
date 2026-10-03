package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.embedding.TeamEmbeddingService;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.model.EmbeddingCapabilityProbe;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.model.OpenAiCompatibleEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Explicit Spring assembly contract for the M10-I01a embedding boundary. */
class ModelEmbeddingApplicationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelEmbeddingApplicationConfiguration.class)
            .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
            .withBean(
                    ModelConnectionRepository.class, () -> mock(ModelConnectionRepository.class))
            .withBean(
                    ModelProviderDefinitionRepository.class,
                    () -> mock(ModelProviderDefinitionRepository.class))
            .withBean(
                    ModelCatalogEntryRepository.class,
                    () -> mock(ModelCatalogEntryRepository.class))
            .withBean(
                    ModelPriceScheduleRepository.class,
                    () -> mock(ModelPriceScheduleRepository.class))
            .withBean(
                    ModelConnectionCredentialService.class,
                    () -> mock(ModelConnectionCredentialService.class))
            .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class));

    @Test
    void assemblesTheEmbeddingTransportServiceAndCapabilityProbe() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(EmbeddingClient.class)
                .hasSingleBean(OpenAiCompatibleEmbeddingClient.class)
                .hasSingleBean(TeamEmbeddingService.class)
                .hasSingleBean(EmbeddingCapabilityProbe.class));
    }

    @Test
    void rejectsARequestTimeoutBelowTheTransportFloor() {
        runner.withPropertyValues("crewscope.model.embedding.request-timeout=500ms")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void rejectsAnImpossibleRetryBudget() {
        runner.withPropertyValues("crewscope.model.embedding.retry-max-attempts=6")
                .run(context -> context.assertThat().hasFailed());
    }
}

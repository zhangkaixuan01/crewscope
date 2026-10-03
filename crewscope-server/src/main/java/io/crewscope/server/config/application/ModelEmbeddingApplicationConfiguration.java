package io.crewscope.server.config.application;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit constructor wiring for the M10-I01a embedding boundary: the shared
 * OpenAI-compatible transport, the governance-chain team embedding service and the
 * capability probe leg consumed by connection verification.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelEmbeddingProperties.class)
public class ModelEmbeddingApplicationConfiguration {

    @Bean
    EmbeddingClient embeddingClient(ModelEmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingClient(
                properties.validatedConnectTimeout(),
                properties.validatedRequestTimeout(),
                properties.validatedRetryMaxAttempts(),
                properties.validatedRetryBaseDelay());
    }

    @Bean
    TeamEmbeddingService teamEmbeddingService(
            TeamRepository teams,
            ModelConnectionRepository connections,
            ModelProviderDefinitionRepository providers,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionCredentialService credentials,
            EmbeddingClient client,
            DomainEventStore events,
            OutboxRepository outbox,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new TeamEmbeddingService(
                teams,
                connections,
                providers,
                catalogs,
                prices,
                credentials,
                client,
                events,
                outbox,
                transactions,
                timeProvider);
    }

    @Bean
    EmbeddingCapabilityProbe embeddingCapabilityProbe(
            ObjectProvider<TeamEmbeddingService> teamEmbedding) {
        // Resolved lazily, not at construction: the credential service consumes this probe
        // for verification while the embedding service consumes the credential service —
        // an eager dependency here would close a circular reference.
        return (provider, connection, handle, correlationId) -> teamEmbedding
                .getObject()
                .probeCapability(provider, connection, handle, correlationId);
    }
}

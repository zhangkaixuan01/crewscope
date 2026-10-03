package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeIndexControlService;
import io.crewscope.application.retrieval.KnowledgeIndexJobRepository;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.application.retrieval.KnowledgeIndexStatusCatalog;
import io.crewscope.application.retrieval.KnowledgeIndexWorker;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.application.retrieval.RepositoryContentPort;
import io.crewscope.application.retrieval.RepositoryGenerationStore;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.embedding.TeamEmbeddingService;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.persistence.knowledge.KnowledgeIndexInvalidationConsumer;
import io.crewscope.infrastructure.persistence.retrieval.JdbcKnowledgeIndexStatusCatalog;
import io.crewscope.infrastructure.persistence.retrieval.JdbcRepositoryGenerationStoreAdapter;
import io.crewscope.infrastructure.persistence.retrieval.PgVectorRepositoryChunkStore;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.GitRepositoryContentAdapter;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import io.crewscope.server.config.runtime.WorkerCapableProfileCondition;
import io.crewscope.server.observability.KnowledgeIndexHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Server composition for the durable knowledge index (M10-I01b/I01c). The enqueue service,
 * the control plane and the health projection always exist; the invalidation consumer and the derived status
 * catalog only assemble when the vector store is enabled, because both would otherwise
 * touch vector-chain tables that a non-pgvector deployment never created. Leased worker
 * execution is worker-profile only and needs both switches — the switch matrix's single
 * illegal combination ({@code index} without {@code vector}) never enqueues refresh
 * jobs and reports {@code DOWN} through {@link KnowledgeIndexHealthIndicator}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeIndexProperties.class)
public class KnowledgeIndexConfiguration {

    @Bean
    KnowledgeIndexJobService knowledgeIndexJobService(
            KnowledgeIndexJobRepository jobs,
            TeamEmbeddingService embeddings,
            KnowledgeRepository knowledge,
            TimeProvider timeProvider,
            KnowledgeIndexProperties properties,
            @Value("${crewscope.knowledge.vector.enabled:false}") boolean vectorEnabled) {
        // Refresh enqueues need both switches; cleanup enqueues are never gated here —
        // the consumer itself only exists when the vector store does.
        return new KnowledgeIndexJobService(
                jobs,
                embeddings,
                knowledge,
                timeProvider,
                properties.isEnabled() && vectorEnabled);
    }

    /**
     * Always assembled (M10-I01c): reads stay available with both switches off, and
     * trigger commands answer 202-with-zero instead of failing while the gate is
     * closed — the switch matrix lives in the enqueue service, not the wiring.
     */
    @Bean
    KnowledgeIndexControlService knowledgeIndexControlService(
            KnowledgeIndexJobService enqueue,
            KnowledgeIndexJobRepository jobs,
            RepositoryBindingRepository bindings,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new KnowledgeIndexControlService(
                enqueue,
                jobs,
                bindings,
                teams,
                memberships,
                roles,
                grants,
                transactions,
                timeProvider);
    }

    @Bean
    @ConditionalOnProperty(
            name = "crewscope.knowledge.vector.enabled",
            havingValue = "true",
            matchIfMissing = false)
    DomainEventConsumer knowledgeIndexInvalidationConsumer(
            ObjectMapper objectMapper, KnowledgeIndexJobService index) {
        return new KnowledgeIndexInvalidationConsumer(objectMapper, index);
    }

    @Bean
    @ConditionalOnProperty(
            name = "crewscope.knowledge.vector.enabled",
            havingValue = "true",
            matchIfMissing = false)
    KnowledgeIndexStatusCatalog knowledgeIndexStatusCatalog(
            NamedParameterJdbcTemplate jdbc) {
        return new JdbcKnowledgeIndexStatusCatalog(jdbc);
    }

    @Bean
    KnowledgeIndexHealthIndicator knowledgeIndexHealthIndicator(
            KnowledgeIndexProperties properties,
            @Value("${crewscope.knowledge.vector.enabled:false}") boolean vectorEnabled) {
        return new KnowledgeIndexHealthIndicator(properties.isEnabled(), vectorEnabled);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnProperty(
            name = {"crewscope.knowledge.index.enabled", "crewscope.knowledge.vector.enabled"},
            havingValue = "true")
    RepositoryContentPort repositoryContentPort(
            NamedParameterJdbcTemplate jdbc,
            ManagedRepositoryResolver resolver,
            GitCommandExecutor gitCommands) {
        return new GitRepositoryContentAdapter(jdbc, resolver, gitCommands);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnProperty(
            name = {"crewscope.knowledge.index.enabled", "crewscope.knowledge.vector.enabled"},
            havingValue = "true")
    RepositoryChunkVectorStore repositoryChunkVectorStore(JdbcTemplate jdbcTemplate) {
        return new PgVectorRepositoryChunkStore(jdbcTemplate);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnProperty(
            name = {"crewscope.knowledge.index.enabled", "crewscope.knowledge.vector.enabled"},
            havingValue = "true")
    RepositoryGenerationStore repositoryGenerationStore(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        // Not component-scanned: the activation purge deletes vector-chain rows, so this
        // adapter only exists behind the same switches as the worker itself.
        return new JdbcRepositoryGenerationStoreAdapter(jdbc, transactions);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnProperty(
            name = {"crewscope.knowledge.index.enabled", "crewscope.knowledge.vector.enabled"},
            havingValue = "true")
    KnowledgeIndexWorker knowledgeIndexWorker(
            KnowledgeIndexJobRepository jobs,
            TeamEmbeddingService embeddings,
            KnowledgeRepository knowledge,
            KnowledgeEmbeddingVectorStore knowledgeVectors,
            RepositoryContentPort repositoryContent,
            RepositoryChunkVectorStore repositoryChunkVectors,
            RepositoryGenerationStore generations,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            OperationalTelemetry telemetry,
            KnowledgeIndexProperties properties) {
        properties.getWorker().validatedPollInterval();
        return new KnowledgeIndexWorker(
                jobs,
                embeddings,
                knowledge,
                knowledgeVectors,
                repositoryContent,
                repositoryChunkVectors,
                generations,
                transactions,
                timeProvider,
                telemetry,
                properties.getWorker().validatedWorkerId(),
                properties.getWorker().validatedLeaseDuration(),
                GenerationRetentionPolicy.DEFAULT,
                properties.validatedMaxChunksPerGeneration(),
                properties.chunkingPolicy());
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnProperty(
            name = {
                "crewscope.knowledge.index.enabled",
                "crewscope.knowledge.vector.enabled",
                "crewscope.knowledge.index.worker.enabled"
            },
            havingValue = "true")
    KnowledgeIndexWorkerScheduler knowledgeIndexWorkerScheduler(
            KnowledgeIndexWorker worker, OperationalTelemetry telemetry) {
        return new KnowledgeIndexWorkerScheduler(worker, telemetry);
    }
}

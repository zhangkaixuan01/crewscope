package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.TeamEmbeddingService;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.retrieval.GenerationCatalog;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.server.observability.KnowledgeRetrievalHealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Server composition for unified knowledge retrieval (M10-A01). The service is always
 * assembled and degrades internally — a closed {@code retrieval} switch or an absent
 * vector store answers {@code RETRIEVAL_DISABLED} instead of failing — so both vector
 * stores arrive as nullable {@link ObjectProvider} lookups exactly like the embedding
 * capability probe. The chunking policy hash mirrors the enqueue site's
 * {@link ChunkingPolicy#defaults()} (see {@code KnowledgeIndexJobService}), keeping the
 * ACTIVE-generation lookup and the enqueue on one coordinate. The health indicator
 * reports the switch matrix's single illegal combination (retrieval without vector)
 * as {@code DOWN} with the operator action.
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeRetrievalConfiguration {

    @Bean
    KnowledgeRetrievalService knowledgeRetrievalService(
            TeamEmbeddingService embeddings,
            GenerationCatalog generations,
            ObjectProvider<KnowledgeEmbeddingVectorStore> knowledgeVectors,
            ObjectProvider<RepositoryChunkVectorStore> chunkVectors,
            KnowledgeRepository knowledge,
            RepositoryBindingRepository bindings,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            @Value("${crewscope.knowledge.retrieval.enabled:false}") boolean retrievalEnabled) {
        return new KnowledgeRetrievalService(
                embeddings,
                generations,
                knowledgeVectors.getIfAvailable(),
                chunkVectors.getIfAvailable(),
                knowledge,
                bindings,
                teams,
                memberships,
                ChunkingPolicy.defaults(),
                retrievalEnabled);
    }

    @Bean
    KnowledgeRetrievalHealthIndicator knowledgeRetrievalHealthIndicator(
            @Value("${crewscope.knowledge.retrieval.enabled:false}") boolean retrievalEnabled,
            ObjectProvider<KnowledgeEmbeddingVectorStore> knowledgeVectors,
            ObjectProvider<RepositoryChunkVectorStore> chunkVectors) {
        return new KnowledgeRetrievalHealthIndicator(
                retrievalEnabled,
                knowledgeVectors.getIfAvailable() != null,
                chunkVectors.getIfAvailable() != null);
    }
}

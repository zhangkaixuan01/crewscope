package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.TeamEmbeddingService;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.retrieval.GenerationCatalog;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.server.observability.KnowledgeRetrievalHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Assembly contract for unified retrieval (M10-A01): the service assembles behind no
 * switch at all — an absent vector store becomes the internal RETRIEVAL_DISABLED
 * degradation, never a startup failure — while the switch matrix's illegal combination
 * (retrieval without vector) reports DOWN with the operator action.
 */
class KnowledgeRetrievalConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeRetrievalConfiguration.class)
            .withBean(TeamEmbeddingService.class, () -> mock(TeamEmbeddingService.class))
            .withBean(GenerationCatalog.class, () -> mock(GenerationCatalog.class))
            .withBean(KnowledgeRepository.class, () -> mock(KnowledgeRepository.class))
            .withBean(RepositoryBindingRepository.class,
                    () -> mock(RepositoryBindingRepository.class))
            .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
            .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class));

    @Test
    void theServiceAssemblesBehindNoSwitchAndDegradesInternally() {
        runner.run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(KnowledgeRetrievalService.class)
                    .hasSingleBean(KnowledgeRetrievalHealthIndicator.class);
            // Closed switch, absent stores: healthy deployment, degraded searches.
            assertTrue(Status.UP.equals(
                    context.getBean(KnowledgeRetrievalHealthIndicator.class)
                            .health().getStatus()));
        });
    }

    @Test
    void retrievalWithoutVectorReportsDown() {
        runner.withPropertyValues("crewscope.knowledge.retrieval.enabled=true").run(context -> {
            context.assertThat().hasNotFailed();
            KnowledgeRetrievalHealthIndicator indicator =
                    context.getBean(KnowledgeRetrievalHealthIndicator.class);
            assertTrue(Status.DOWN.equals(indicator.health().getStatus()),
                    "retrieval without vector is DOWN, not silently healthy");
            assertTrue(indicator.health().getDetails().toString().contains("vector"));
        });
    }

    @Test
    void retrievalWithBothStoresReportsUp() {
        runner.withPropertyValues("crewscope.knowledge.retrieval.enabled=true")
                .withBean(KnowledgeEmbeddingVectorStore.class,
                        () -> mock(KnowledgeEmbeddingVectorStore.class))
                .withBean(RepositoryChunkVectorStore.class,
                        () -> mock(RepositoryChunkVectorStore.class))
                .run(context -> {
                    context.assertThat()
                            .hasNotFailed()
                            .hasSingleBean(KnowledgeRetrievalService.class);
                    assertTrue(Status.UP.equals(
                            context.getBean(KnowledgeRetrievalHealthIndicator.class)
                                    .health().getStatus()));
                });
    }
}

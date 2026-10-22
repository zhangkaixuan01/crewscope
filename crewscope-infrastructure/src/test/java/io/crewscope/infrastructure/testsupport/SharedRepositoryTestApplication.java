package io.crewscope.infrastructure.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.infrastructure.event.JdbcOutboxClaimStore;
import io.crewscope.infrastructure.event.OutboxPublisherConfiguration;
import io.crewscope.infrastructure.event.projection.AuditEventProjector;
import io.crewscope.infrastructure.event.projection.JdbcProjectionCheckpointStore;
import io.crewscope.infrastructure.event.projection.ProjectionConfiguration;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.knowledge.JdbcKnowledgeRepositoryAdapter;
import io.crewscope.infrastructure.persistence.memory.JdbcAgentMemoryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.retrieval.JdbcInjectionManifestRepositoryAdapter;
import io.crewscope.infrastructure.persistence.retrieval.JdbcInjectionReferenceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.review.JdbcReviewQueryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.review.JdbcReviewRepositoryAdapter;
import io.crewscope.infrastructure.persistence.review.ReviewPackageConfiguration;
import io.crewscope.infrastructure.persistence.review.ReviewPersistenceConfiguration;
import io.crewscope.infrastructure.persistence.skill.JdbcTeamSkillRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcTaskEventRepository;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * One shared boot configuration for the outbox-disabled repository integration tests, so every
 * class joins the same cached Spring TestContext instead of cold-starting a private application.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
    JdbcKnowledgeRepositoryAdapter.class,
    JdbcAgentMemoryRepositoryAdapter.class,
    ReviewPackageConfiguration.class,
    JdbcReviewQueryRepositoryAdapter.class,
    JdbcReviewRepositoryAdapter.class,
    JdbcDomainEventStore.class,
    JdbcOutboxRepository.class,
    JdbcTaskEventRepository.class,
    SpringTransactionExecutor.class,
    ReviewPersistenceConfiguration.class,
    JdbcOutboxClaimStore.class,
    JdbcProjectionCheckpointStore.class,
    AuditEventProjector.class,
    ProjectionConfiguration.class,
    OutboxPublisherConfiguration.class,
    JdbcTeamSkillRepositoryAdapter.class,
    JdbcInjectionManifestRepositoryAdapter.class,
    JdbcInjectionReferenceRepositoryAdapter.class
})
public class SharedRepositoryTestApplication {

    /**
     * Evidence serialization mapper for the injection adapters (carried over from the retrieval
     * tests' private applications). The auto-configured {@code jacksonObjectMapper} stays
     * {@code @Primary}, so type-by-type injection elsewhere is unaffected.
     */
    @Bean
    ObjectMapper evidenceObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}

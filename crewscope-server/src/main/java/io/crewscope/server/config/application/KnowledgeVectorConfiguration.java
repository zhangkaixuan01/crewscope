package io.crewscope.server.config.application;

import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.infrastructure.persistence.knowledge.PgVectorKnowledgeEmbeddingStore;
import io.crewscope.server.observability.KnowledgeVectorHealthIndicator;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Optional pgvector assembly for the knowledge embedding store (M10-I01a). The
 * migration runner is constructed unconditionally and gates itself internally (see
 * {@link KnowledgeVectorMigrationRunner}); the store and its health indicator only
 * exist when the operator opted in, so non-opting deployments see neither bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeVectorProperties.class)
public class KnowledgeVectorConfiguration {

    @Bean
    KnowledgeVectorMigrationRunner knowledgeVectorMigrationRunner(
            DataSource dataSource,
            KnowledgeVectorProperties properties,
            @Value("${spring.flyway.enabled:true}") boolean flywayEnabled) {
        return new KnowledgeVectorMigrationRunner(
                dataSource, properties.isEnabled(), flywayEnabled);
    }

    @Bean
    @ConditionalOnProperty(
            name = "crewscope.knowledge.vector.enabled",
            havingValue = "true",
            matchIfMissing = false)
    KnowledgeEmbeddingVectorStore knowledgeEmbeddingVectorStore(JdbcTemplate jdbcTemplate) {
        return new PgVectorKnowledgeEmbeddingStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(
            name = "crewscope.knowledge.vector.enabled",
            havingValue = "true",
            matchIfMissing = false)
    KnowledgeVectorHealthIndicator knowledgeVectorHealthIndicator(
            KnowledgeVectorMigrationRunner runner) {
        return new KnowledgeVectorHealthIndicator(runner);
    }
}

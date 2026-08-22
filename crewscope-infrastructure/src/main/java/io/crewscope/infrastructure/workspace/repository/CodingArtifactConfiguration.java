package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared server/worker assembly for governed Coding Artifact publication and consumption. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ArtifactStore.class)
@EnableConfigurationProperties(CodingArtifactProperties.class)
public class CodingArtifactConfiguration {

    @Bean
    @ConditionalOnMissingBean(CodingArtifactPublisher.class)
    CodingArtifactPublisher codingArtifactPublisher(
            ArtifactStore artifactStore, CodingArtifactProperties properties) {
        return new CodingArtifactPublisher(artifactStore, properties);
    }

    @Bean
    @ConditionalOnBean({RuntimeArtifactRepository.class, AgentRunRepository.class})
    @ConditionalOnMissingBean(CodingRuntimeArtifactRegistrar.class)
    CodingRuntimeArtifactRegistrar codingRuntimeArtifactRegistrar(
            RuntimeArtifactRepository artifacts, AgentRunRepository runs) {
        return new CodingRuntimeArtifactRegistrar(artifacts, runs);
    }

    @Bean
    @ConditionalOnMissingBean(CodingArtifactReader.class)
    CodingArtifactReader codingArtifactReader(
            ArtifactStore artifactStore, CodingArtifactProperties properties) {
        return new CodingArtifactReader(artifactStore, properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(CodingArtifactLifecycle.class)
    CodingArtifactLifecycle codingArtifactLifecycle(ArtifactStore artifactStore) {
        return new CodingArtifactLifecycle(artifactStore);
    }
}

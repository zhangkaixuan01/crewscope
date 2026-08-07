package io.crewscope.infrastructure.artifact;

import io.crewscope.application.artifact.ArtifactStore;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Production wiring for the development Filesystem ArtifactStore adapter. */
@Configuration(proxyBeanMethods = false)
public class ArtifactStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(ArtifactStore.class)
    ArtifactStore filesystemArtifactStore(
            @Value("${crewscope.artifact.filesystem.root:./var/crewscope/artifacts}") String root,
            ObjectMapper objectMapper) {
        return new FilesystemArtifactStore(Path.of(root), objectMapper, Clock.systemUTC());
    }
}

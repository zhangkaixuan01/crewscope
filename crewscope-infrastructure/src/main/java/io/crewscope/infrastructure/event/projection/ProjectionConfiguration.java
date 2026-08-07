package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.publication.DomainEventConsumer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Registers M0's durable AuditEvent projection as an in-process event consumer. */
@Configuration(proxyBeanMethods = false)
public class ProjectionConfiguration {

    @Bean
    ProjectionEventJsonMapper projectionEventJsonMapper(ObjectMapper objectMapper) {
        return new ProjectionEventJsonMapper(objectMapper);
    }

    @Bean
    DomainEventConsumer auditEventProjectionRunner(
            AuditEventProjector auditEventProjector,
            JdbcProjectionCheckpointStore checkpointStore,
            ProjectionEventJsonMapper eventMapper) {
        return new CheckpointedProjectionRunner(
                auditEventProjector,
                checkpointStore,
                eventMapper,
                Clock.systemUTC());
    }
}

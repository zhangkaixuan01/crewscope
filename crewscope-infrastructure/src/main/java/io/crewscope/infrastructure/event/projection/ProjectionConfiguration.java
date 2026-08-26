package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.activity.ActivityEventTypeRegistry;
import io.crewscope.application.activity.CrewScopeActivityEventTypes;
import io.crewscope.application.audit.AuditEventTypeRegistry;
import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.inbox.CrewScopeInboxEventTypes;
import io.crewscope.application.inbox.InboxEventTypeRegistry;
import io.crewscope.application.notification.CrewScopeNotificationIntentPolicies;
import io.crewscope.application.notification.NotificationIntentPolicyRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Registers checkpointed Audit and generation-aware read-model event consumers. */
@Configuration(proxyBeanMethods = false)
public class ProjectionConfiguration {

    @Bean
    ProjectionEventJsonMapper projectionEventJsonMapper(ObjectMapper objectMapper) {
        return new ProjectionEventJsonMapper(objectMapper);
    }

    @Bean
    ActivityEventTypeRegistry activityEventTypeRegistry() {
        return CrewScopeActivityEventTypes.reviewedRegistry();
    }

    @Bean
    AuditEventTypeRegistry auditEventTypeRegistry() {
        return CrewScopeAuditEventTypes.reviewedRegistry();
    }

    @Bean
    InboxEventTypeRegistry inboxEventTypeRegistry() {
        return CrewScopeInboxEventTypes.reviewedRegistry();
    }

    @Bean
    NotificationIntentPolicyRegistry notificationIntentPolicyRegistry() {
        return CrewScopeNotificationIntentPolicies.fixedRegistry();
    }

    @Bean
    @ConditionalOnBean(GenerationAwareProjectionRunnerFactory.class)
    DomainEventConsumer generationAwareProjectionRouter(
            ObjectProvider<GenerationAwareProjectionHandler> handlers,
            GenerationAwareProjectionRunnerFactory factory) {
        List<GenerationAwareProjectionHandler> registered = handlers.orderedStream().toList();
        return new GenerationAwareProjectionRouter(registered, factory);
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

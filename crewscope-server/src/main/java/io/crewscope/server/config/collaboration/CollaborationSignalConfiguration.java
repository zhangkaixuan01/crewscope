package io.crewscope.server.config.collaboration;

import io.crewscope.application.collaboration.CollaborationSignalSink;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.identity.CurrentAccountSnapshotReader;
import io.crewscope.infrastructure.persistence.collaboration.CollaborationResourceChangeConsumer;
import io.crewscope.server.collaboration.CollaborationDisplayNameResolver;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Collaboration signal wiring (M11-A01). Like {@link CollaborationSubscriptionConfiguration}
 * this lives outside {@code server.collaboration} on purpose: the display-name resolver needs
 * the JPA-backed account snapshot reader, which the WebSocket test fixture (JPA excluded) must
 * never load — the fixture provides its own fake resolver instead.
 *
 * <p>Both beans exist only when the channel is enabled: a disabled deployment registers no
 * {@code DomainEventConsumer}, so the dispatcher writes no receipts for the collaboration
 * consumer name at all (the same zero-footprint rule as the knowledge consumer).</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "crewscope.collaboration-realtime",
    name = "enabled",
    havingValue = "true")
public class CollaborationSignalConfiguration {

  @Bean
  CollaborationDisplayNameResolver collaborationDisplayNameResolver(
      AuthenticationSubjectExtractor subjectExtractor,
      CurrentAccountSnapshotReader accounts) {
    return new RepositoryCollaborationDisplayNameResolver(subjectExtractor, accounts);
  }

  /**
   * The outbox half of the signal pipeline. The {@link CollaborationSignalSink} is the
   * in-memory fanout declared by the WebSocket configuration under the same switch — with the
   * channel off there is neither a consumer nor a sink, and no receipt rows are ever written.
   */
  @Bean
  DomainEventConsumer collaborationResourceChangeConsumer(
      ObjectMapper objectMapper,
      CollaborationSignalSink sink,
      ConversationRepository conversations) {
    return new CollaborationResourceChangeConsumer(objectMapper, sink, conversations);
  }
}

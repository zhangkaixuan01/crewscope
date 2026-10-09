package io.crewscope.server.config.collaboration;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.server.api.TeamRequestIdentityResolver;
import io.crewscope.server.collaboration.CollaborationSubscriptionAuthorizer;
import io.crewscope.server.collaboration.RepositoryCollaborationSubscriptionAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Product subscription authorization wiring (M11-I01b). This lives outside the
 * {@code server.collaboration} package on purpose: it needs the JPA-backed authorization beans
 * (resolver, policies, repositories), which the WebSocket test fixture — a @SpringBootConfiguration
 * rooted at the collaboration package with JPA excluded — must never load. The fixture provides
 * its own fake {@link CollaborationSubscriptionAuthorizer} instead; the production application
 * scans both packages and assembles the real one only when the channel is enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "crewscope.collaboration-realtime",
    name = "enabled",
    havingValue = "true")
public class CollaborationSubscriptionConfiguration {

  @Bean
  CollaborationSubscriptionAuthorizer collaborationSubscriptionAuthorizer(
      TeamRequestIdentityResolver identityResolver,
      WorkItemAccessPolicy workItemAccessPolicy,
      WorkItemRepository workItemRepository,
      ConversationApplicationService conversationService) {
    return new RepositoryCollaborationSubscriptionAuthorizer(
        identityResolver, workItemAccessPolicy, workItemRepository, conversationService);
  }
}

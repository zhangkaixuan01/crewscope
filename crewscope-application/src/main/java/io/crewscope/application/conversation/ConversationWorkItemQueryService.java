package io.crewscope.application.conversation;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Serves both directions of Conversation/WorkItem links through each resource's own policy. */
public final class ConversationWorkItemQueryService {

  private final ConversationApplicationService conversationService;
  private final ConversationWorkItemLinkRepository linkRepository;
  private final WorkItemAccessPolicy workItemAccessPolicy;
  private final TransactionExecutor transactionExecutor;

  public ConversationWorkItemQueryService(
      ConversationApplicationService conversationService,
      ConversationWorkItemLinkRepository linkRepository,
      WorkItemAccessPolicy workItemAccessPolicy,
      TransactionExecutor transactionExecutor) {
    this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
    this.linkRepository = Objects.requireNonNull(linkRepository, "linkRepository");
    this.workItemAccessPolicy =
        Objects.requireNonNull(workItemAccessPolicy, "workItemAccessPolicy");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
  }

  /** Lists linked WorkItems only after the containing Conversation is currently readable. */
  public List<ConversationWorkItemAssociation> byConversation(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      ConversationId conversationId) {
    return transactionExecutor.required(
        () -> {
          Conversation conversation =
              conversationService
                  .get(context, organizationId, teamId, conversationId)
                  .conversation();
          return linkRepository.findLinksByConversation(organizationId, conversation.id()).stream()
              .map(link -> associationFromConversation(context, conversation, link))
              .toList();
        });
  }

  /** Lists only linked Conversations that remain discoverable to the WorkItem viewer. */
  public List<ConversationWorkItemAssociation> byWorkItem(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {
    return transactionExecutor.required(
        () -> {
          WorkItem workItem =
              workItemAccessPolicy.requireVisibleWorkItem(
                  context, organizationId, teamId, projectId, workItemId);
          List<ConversationWorkItemAssociation> visible = new ArrayList<>();
          for (ConversationWorkItemLink link :
              linkRepository.findLinksByWorkItem(organizationId, workItem.id())) {
            requireLinkScope(link, workItem);
            try {
              Conversation conversation =
                  conversationService
                      .get(context, organizationId, teamId, link.conversationId())
                      .conversation();
              visible.add(new ConversationWorkItemAssociation(link, conversation, workItem));
            } catch (AggregateNotFoundException | PolicyDeniedException inaccessible) {
              // A WorkItem must not disclose a PRIVATE Conversation the caller cannot discover.
            }
          }
          return List.copyOf(visible);
        });
  }

  private ConversationWorkItemAssociation associationFromConversation(
      TeamAccessContext context,
      Conversation conversation,
      ConversationWorkItemLink link) {
    if (!link.scope().equals(conversation.scope())
        || !link.conversationId().equals(conversation.id())) {
      throw invalidRepositoryResult();
    }
    WorkItem workItem =
        workItemAccessPolicy.requireVisibleWorkItem(
            context,
            conversation.scope().organizationId(),
            conversation.scope().teamId(),
            link.workProjectId(),
            link.workItemId());
    return new ConversationWorkItemAssociation(link, conversation, workItem);
  }

  private static void requireLinkScope(ConversationWorkItemLink link, WorkItem workItem) {
    if (!link.workItemId().equals(workItem.id())
        || !link.workProjectId().equals(workItem.scope().projectId())
        || !link.scope().organizationId().equals(workItem.scope().organizationId())
        || !link.scope().teamId().equals(workItem.scope().teamId())
        || !link.scope().workspaceId().equals(workItem.scope().workspaceId())) {
      throw invalidRepositoryResult();
    }
  }

  private static DomainValidationException invalidRepositoryResult() {
    return new DomainValidationException(
        "conversationWorkItemLink.repositoryResult",
        "must remain inside the requested Conversation and WorkItem scope");
  }
}

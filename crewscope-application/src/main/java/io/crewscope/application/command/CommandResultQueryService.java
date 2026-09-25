package io.crewscope.application.command;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Same actor AND current resource visibility; platform administration does not bypass ownership. */
public final class CommandResultQueryService {
  private final CommandResultStore results;
  private final WorkItemAccessPolicy workAccess;
  private final ConversationApplicationService conversations;
  private final TeamMemberRepository members;
  private final TransactionExecutor transactions;

  public CommandResultQueryService(CommandResultStore results, WorkItemAccessPolicy workAccess,
      ConversationApplicationService conversations, TeamMemberRepository members,
      TransactionExecutor transactions) {
    this.results = Objects.requireNonNull(results, "results");
    this.workAccess = Objects.requireNonNull(workAccess, "workAccess");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.members = Objects.requireNonNull(members, "members");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  public Optional<CommandResult> find(TeamAccessContext access, OrganizationId organizationId,
      IdempotencyKey key) {
    Objects.requireNonNull(access, "access");
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(key, "key");
    if (access.actor().type() != PrincipalType.USER
        || access.actor().status() != PrincipalStatus.ACTIVE
        || !access.actor().scope().organizationId().equals(organizationId)) return Optional.empty();
    return transactions.required(() -> {
      Optional<CommandResult> result = results.findResult(organizationId, key, access.actor().id());
      if (result.isEmpty()) return Optional.empty();
      CommandResult value = result.orElseThrow();
      // Also enforce the port contract for alternate adapters; no key or target leaks on mismatch.
      if (!value.actorId().equals(access.actor().id())
          || !value.organizationId().equals(organizationId) || !value.idempotencyKey().equals(key)) {
        return Optional.empty();
      }
      try {
        switch (value.resourceType()) {
          case WORK_PROJECT -> workAccess.requireVisibleProject(access, organizationId,
              value.teamId(), value.projectId().orElseThrow());
          case WORK_ITEM -> workAccess.requireVisibleWorkItem(access, organizationId,
              value.teamId(), value.projectId().orElseThrow(), new WorkItemId(value.resourceId()));
          case CONVERSATION -> conversations.get(access, organizationId, value.teamId(),
              new ConversationId(value.resourceId()));
          case TEAM_MEMBER -> requireSelfMember(access, organizationId, value.teamId(),
              value.resourceId());
        }
        return result;
      } catch (AggregateNotFoundException | PolicyDeniedException denied) {
        return Optional.empty();
      }
    });
  }

  /** A TEAM_MEMBER coordinate stays visible only to the member named by it, while participating. */
  private void requireSelfMember(TeamAccessContext access, OrganizationId organizationId,
      TeamId teamId, UUID memberId) {
    TeamMember member = members
        .findByTeamAndUserPrincipalId(organizationId, teamId, access.actor().id())
        .filter(TeamMember::canParticipate)
        .orElseThrow(() -> new PolicyDeniedException("read the own membership result"));
    if (!member.id().value().equals(memberId)) {
      throw new PolicyDeniedException("read the own membership result");
    }
  }
}

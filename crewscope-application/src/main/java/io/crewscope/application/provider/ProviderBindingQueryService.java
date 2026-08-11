package io.crewscope.application.provider;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;
import java.util.Optional;

/** Resolves the Team default Workspace Binding only from current membership and registry facts. */
public final class ProviderBindingQueryService {

  private final BuiltInProviderRegistration registration;
  private final TeamRepository teamRepository;
  private final WorkspaceRepository workspaceRepository;
  private final TeamMembershipQuery membershipQuery;
  private final ProviderBindingResolver resolver;
  private final TransactionExecutor transactionExecutor;

  public ProviderBindingQueryService(
      BuiltInProviderRegistration registration,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMembershipQuery membershipQuery,
      ProviderBindingResolver resolver,
      TransactionExecutor transactionExecutor) {
    this.registration = Objects.requireNonNull(registration, "registration");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
  }

  /** Returns RESOLVED, NOT_FOUND or AMBIGUOUS without mutating or repairing Provider facts. */
  public ProviderBindingLookup resolveDefault(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    TeamAccessContext trusted = Objects.requireNonNull(context, "context");
    OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
    TeamId teamIdentifier = Objects.requireNonNull(teamId, "teamId");
    return transactionExecutor.required(
        () -> resolveInTransaction(trusted, organization, teamIdentifier));
  }

  private ProviderBindingLookup resolveInTransaction(
      TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    Principal actor = context.actor();
    if (!actor.scope().organizationId().equals(organizationId)
        || actor.type() != PrincipalType.USER
        || !actor.canAct()) {
      throw new PolicyDeniedException("query Provider Bindings in this Organization");
    }
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    Team team =
        teamRepository
            .findById(organizationId, teamId)
            .filter(Team::isActive)
            .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    membershipQuery.findByTeam(organizationId, teamId).stream()
        .filter(value -> value.userPrincipalId().equals(actor.id()))
        .filter(TeamMember::canParticipate)
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("query this Team Provider Binding"));
    Workspace workspace =
        workspaceRepository
            .findById(organizationId, team.defaultWorkspaceId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Workspace", team.defaultWorkspaceId()));
    ProviderBindingResolution resolution =
        resolver.resolve(
            new ProviderBindingResolutionRequest(
                organizationId,
                teamId,
                workspace.id(),
                Optional.empty(),
                ProviderOwner.team(team),
                registration.type(),
                Optional.empty(),
                registration.workspaceAccess(workspace.id()),
                Optional.empty(),
                Optional.empty()));
    return new ProviderBindingLookup(registration, resolution);
  }
}

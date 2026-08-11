package io.crewscope.application.team;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.provider.TeamProviderInitializer;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.List;
import java.util.Objects;

/** Persists the complete Team foundation inside one required application transaction. */
public final class TeamCreationService {

  private final TeamRepository teamRepository;
  private final WorkspaceRepository workspaceRepository;
  private final TeamMemberRepository teamMemberRepository;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;
  private final DefaultPersonalAgentRepository defaultPersonalAgentRepository;
  private final TeamProviderInitializer providerInitializer;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public TeamCreationService(
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMemberRepository teamMemberRepository,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DefaultPersonalAgentRepository defaultPersonalAgentRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this(
        teamRepository,
        workspaceRepository,
        teamMemberRepository,
        teamRoleRepository,
        memberRoleRepository,
        defaultPersonalAgentRepository,
        (team, workspace, actor) -> {},
        transactionExecutor,
        timeProvider);
  }

  public TeamCreationService(
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMemberRepository teamMemberRepository,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DefaultPersonalAgentRepository defaultPersonalAgentRepository,
      TeamProviderInitializer providerInitializer,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.teamMemberRepository =
        Objects.requireNonNull(teamMemberRepository, "teamMemberRepository");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository =
        Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    this.defaultPersonalAgentRepository =
        Objects.requireNonNull(defaultPersonalAgentRepository, "defaultPersonalAgentRepository");
    this.providerInitializer = Objects.requireNonNull(providerInitializer, "providerInitializer");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /**
   * Creates and persists Team, default Workspace, owner Membership, built-in roles, owner grant and
   * the owner's default Personal Agent. Authentication adapters must supply the server-resolved
   * creator Principal.
   */
  public TeamInitialization create(Principal creator, CreateTeamCommand command) {
    Principal requiredCreator = Objects.requireNonNull(creator, "creator");
    CreateTeamCommand requiredCommand = Objects.requireNonNull(command, "command");
    return transactionExecutor.required(
        () ->
            persist(
                TeamInitialization.create(
                    requiredCreator, requiredCommand.name(), timeProvider.now()),
                requiredCreator));
  }

  /** Completes one locked migrated Team using the same atomic foundation as normal creation. */
  public TeamInitialization completeLegacy(
      UninitializedTeam team, Principal owner, Principal initializedBy) {
    UninitializedTeam requiredTeam = Objects.requireNonNull(team, "team");
    Principal requiredOwner = Objects.requireNonNull(owner, "owner");
    Principal requiredActor = Objects.requireNonNull(initializedBy, "initializedBy");
    return transactionExecutor.required(
        () ->
            persist(
                TeamInitialization.completeLegacy(
                    requiredTeam, requiredOwner, requiredActor, timeProvider.now()),
                requiredActor));
  }

  private TeamInitialization persist(TeamInitialization initialization, Principal actor) {
    // Team carries deferred references to its owner Member and default Workspace. The remaining
    // writes then close that graph before the required transaction reaches commit.
    Team team =
        initialization.team().version() == 0
            ? teamRepository.create(initialization.team())
            : teamRepository.update(initialization.team());
    Workspace workspace = workspaceRepository.create(initialization.defaultWorkspace());
    TeamMember ownerMember = teamMemberRepository.create(initialization.ownerMember());
    List<TeamRole> roles = teamRoleRepository.createAll(initialization.builtInRoles());
    MemberRole ownerRole = memberRoleRepository.create(initialization.ownerRole());
    PersonalAgentInitialization personalAgent =
        Objects.requireNonNull(
                defaultPersonalAgentRepository.initializeIfAbsent(
                    initialization.ownerPersonalAgent()),
                "DefaultPersonalAgentRepository.initializeIfAbsent result")
            .requireDefaultFor(ownerMember, workspace);
    providerInitializer.initialize(team, workspace, actor);
    return new TeamInitialization(team, workspace, ownerMember, roles, ownerRole, personalAgent);
  }
}

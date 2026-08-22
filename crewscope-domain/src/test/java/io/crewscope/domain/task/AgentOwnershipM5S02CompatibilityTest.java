package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Executable compatibility proof for the M5-S02 ownership and configuration upgrade shape. */
class AgentOwnershipM5S02CompatibilityTest {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-22T12:00:00Z");
  private static final TemplateVersion PERSONAL_ASSISTANT =
      new TemplateVersion("personal-assistant", 1);
  private static final TemplateVersion TEAM_COORDINATOR =
      new TemplateVersion("team-coordinator", 1);
  private static final TemplateVersion CODING = new TemplateVersion("coding", 1);
  private static final TemplateVersion REVIEWER = new TemplateVersion("reviewer", 1);

  @Test
  void projectsFourOrthogonalAgentInstancesWithoutChangingLegacyIdentity() {
    AgentFixture fixture = AgentFixture.create();

    ProjectedAgent defaultPersonal = UpgradeProjector.projectLegacy(
        fixture.defaultPersonal().profile(), fixture.defaultPersonal().principal());
    ProjectedAgent personalCoding = UpgradeProjector.projectExplicit(
        fixture.personalCoding().profile(), fixture.personalCoding().principal(), CODING);
    ProjectedAgent personalReviewer = UpgradeProjector.projectExplicit(
        fixture.personalReviewer().profile(), fixture.personalReviewer().principal(), REVIEWER);
    ProjectedAgent teamCoding = UpgradeProjector.projectExplicit(
        fixture.teamCoding().profile(), fixture.teamCoding().principal(), CODING);

    assertAgent(
        defaultPersonal,
        fixture.defaultPersonal(),
        OwnershipType.USER,
        RuntimeRole.PERSONAL_ASSISTANT,
        PERSONAL_ASSISTANT);
    assertAgent(
        personalCoding,
        fixture.personalCoding(),
        OwnershipType.USER,
        RuntimeRole.SPECIALIST,
        CODING);
    assertAgent(
        personalReviewer,
        fixture.personalReviewer(),
        OwnershipType.USER,
        RuntimeRole.SPECIALIST,
        REVIEWER);
    assertAgent(
        teamCoding,
        fixture.teamCoding(),
        OwnershipType.TEAM,
        RuntimeRole.SPECIALIST,
        CODING);

    List<ProjectedAgent> agents =
        List.of(defaultPersonal, personalCoding, personalReviewer, teamCoding);
    assertEquals(4, agents.stream().map(ProjectedAgent::profileId).distinct().count());
    assertEquals(4, agents.stream().map(ProjectedAgent::principalId).distinct().count());
    assertEquals(3, agents.stream().map(ProjectedAgent::templateVersion).distinct().count());
    assertTrue(defaultPersonal.defaultProfile());
    assertTrue(agents.stream()
        .filter(agent -> agent.runtimeRole() == RuntimeRole.SPECIALIST)
        .noneMatch(ProjectedAgent::defaultProfile));
  }

  @Test
  void legacyMappingIsDeterministicAndNeverInfersTemplateFromDisplayName() {
    AgentFixture fixture = AgentFixture.create();
    AgentPair misleadingLegacyReviewer = fixture.specialist(
        "Reviewer Agent", Optional.of(fixture.team().ownerMember().id()));
    AgentPair legacyTeamSpecialist = fixture.specialist("Reviewer Agent", Optional.empty());
    AgentPair legacyTeamCoordinator = fixture.teamCoordinator("Coding Agent");

    ProjectedAgent legacySpecialist = UpgradeProjector.projectLegacy(
        misleadingLegacyReviewer.profile(), misleadingLegacyReviewer.principal());
    ProjectedAgent teamSpecialist = UpgradeProjector.projectLegacy(
        legacyTeamSpecialist.profile(), legacyTeamSpecialist.principal());
    ProjectedAgent teamCoordinator = UpgradeProjector.projectLegacy(
        legacyTeamCoordinator.profile(), legacyTeamCoordinator.principal());

    // M2-M4 shipped Coding but not Reviewer as a durable template. Names are untrusted display data.
    assertEquals(CODING, legacySpecialist.templateVersion());
    assertEquals(OwnershipType.USER, legacySpecialist.ownershipType());
    assertEquals(CODING, teamSpecialist.templateVersion());
    assertEquals(OwnershipType.TEAM, teamSpecialist.ownershipType());
    assertEquals(TEAM_COORDINATOR, teamCoordinator.templateVersion());
    assertEquals(RuntimeRole.TEAM_COORDINATOR, teamCoordinator.runtimeRole());
    assertEquals(OwnershipType.TEAM, teamCoordinator.ownershipType());
  }

  @Test
  void resolvesPersonalAndTeamModelBindingsWithoutCrossingUserConnectionBoundary() {
    AgentFixture fixture = AgentFixture.create();
    ProjectedAgent defaultPersonal = UpgradeProjector.projectLegacy(
        fixture.defaultPersonal().profile(), fixture.defaultPersonal().principal());
    ProjectedAgent personalCoding = UpgradeProjector.projectExplicit(
        fixture.personalCoding().profile(), fixture.personalCoding().principal(), CODING);
    ProjectedAgent personalReviewer = UpgradeProjector.projectExplicit(
        fixture.personalReviewer().profile(), fixture.personalReviewer().principal(), REVIEWER);
    ProjectedAgent teamCoding = UpgradeProjector.projectExplicit(
        fixture.teamCoding().profile(), fixture.teamCoding().principal(), CODING);

    ConnectionCoordinate userConnection = ConnectionCoordinate.user(
        "user-deepseek", fixture.owner().id());
    ConnectionCoordinate teamConnection =
        ConnectionCoordinate.shared("team-deepseek", ConnectionOwner.TEAM);
    ConnectionCoordinate organizationConnection =
        ConnectionCoordinate.shared("organization-openai", ConnectionOwner.ORGANIZATION);

    AgentConfigurationShape personalConfiguration = new AgentConfigurationShape(
        1, ModelBinding.direct(userConnection), ModelBinding.unavailable());
    AgentConfigurationShape codingConfiguration = new AgentConfigurationShape(
        3, ModelBinding.direct(userConnection), ModelBinding.direct(teamConnection));
    AgentConfigurationShape reviewerConfiguration = new AgentConfigurationShape(
        2, ModelBinding.direct(organizationConnection), ModelBinding.inheritTeamDefault());
    AgentConfigurationShape teamCodingConfiguration = new AgentConfigurationShape(
        5, ModelBinding.direct(teamConnection), ModelBinding.direct(organizationConnection));

    assertResolution(
        BindingResolver.resolve(
            defaultPersonal,
            personalConfiguration,
            ExecutionScope.PERSONAL,
            Optional.of(teamConnection)),
        ResolutionKind.DIRECT,
        Optional.of(userConnection));
    assertResolution(
        BindingResolver.resolve(
            defaultPersonal,
            personalConfiguration,
            ExecutionScope.TEAM,
            Optional.of(teamConnection)),
        ResolutionKind.ORCHESTRATION_ONLY,
        Optional.empty());
    assertResolution(
        BindingResolver.resolve(
            personalCoding,
            codingConfiguration,
            ExecutionScope.PERSONAL,
            Optional.of(teamConnection)),
        ResolutionKind.DIRECT,
        Optional.of(userConnection));
    assertResolution(
        BindingResolver.resolve(
            personalCoding,
            codingConfiguration,
            ExecutionScope.TEAM,
            Optional.of(organizationConnection)),
        ResolutionKind.DIRECT,
        Optional.of(teamConnection));
    assertResolution(
        BindingResolver.resolve(
            personalReviewer,
            reviewerConfiguration,
            ExecutionScope.PERSONAL,
            Optional.of(teamConnection)),
        ResolutionKind.DIRECT,
        Optional.of(organizationConnection));
    assertResolution(
        BindingResolver.resolve(
            personalReviewer,
            reviewerConfiguration,
            ExecutionScope.TEAM,
            Optional.of(teamConnection)),
        ResolutionKind.INHERITED_TEAM_DEFAULT,
        Optional.of(teamConnection));
    assertResolution(
        BindingResolver.resolve(
            teamCoding,
            teamCodingConfiguration,
            ExecutionScope.PERSONAL,
            Optional.of(teamConnection)),
        ResolutionKind.DIRECT,
        Optional.of(teamConnection));
    assertResolution(
        BindingResolver.resolve(
            teamCoding,
            teamCodingConfiguration,
            ExecutionScope.TEAM,
            Optional.of(teamConnection)),
        ResolutionKind.DIRECT,
        Optional.of(organizationConnection));

    AgentConfigurationShape userKeyForTeam = new AgentConfigurationShape(
        4, ModelBinding.direct(userConnection), ModelBinding.direct(userConnection));
    AgentConfigurationShape anotherOwnerKey = new AgentConfigurationShape(
        6,
        ModelBinding.direct(ConnectionCoordinate.user("another-user", PrincipalId.generate())),
        ModelBinding.unavailable());
    assertThrows(
        IllegalStateException.class,
        () -> BindingResolver.resolve(
            personalCoding,
            anotherOwnerKey,
            ExecutionScope.PERSONAL,
            Optional.of(teamConnection)));
    assertThrows(
        IllegalStateException.class,
        () -> BindingResolver.resolve(
            personalCoding,
            userKeyForTeam,
            ExecutionScope.TEAM,
            Optional.of(teamConnection)));
    assertThrows(
        IllegalStateException.class,
        () -> BindingResolver.resolve(
            personalReviewer,
            reviewerConfiguration,
            ExecutionScope.TEAM,
            Optional.empty()));
    assertThrows(
        IllegalStateException.class,
        () -> BindingResolver.resolve(
            teamCoding,
            userKeyForTeam,
            ExecutionScope.PERSONAL,
            Optional.of(teamConnection)));
  }

  @Test
  void keepsExistingConversationTaskSessionAndPolicyEvidenceReadable() {
    AgentFixture fixture = AgentFixture.create();
    Conversation conversation = Conversation.startPersonal(
        ConversationId.generate(),
        fixture.team().defaultWorkspace(),
        fixture.team().ownerMember(),
        fixture.owner(),
        fixture.team().ownerPersonalAgent(),
        "M5 compatibility",
        ConversationVisibility.PRIVATE,
        CREATED_AT);
    AgentRuntimeSession conversationSession = AgentRuntimeSession.initializePersonal(
        conversation,
        fixture.team().defaultWorkspace(),
        fixture.team().ownerMember(),
        fixture.owner(),
        fixture.team().ownerPersonalAgent(),
        CREATED_AT);

    TaskPlanningFixture taskFixture = new TaskPlanningFixture();
    AgentProfile taskProfile = AgentProfile.reconstitute(
        taskFixture.agentProfileId,
        WorkspaceScope.team(
            taskFixture.task.scope().organizationId(), taskFixture.task.scope().teamId()),
        taskFixture.task.scope().workspaceId(),
        taskFixture.base.executor.id(),
        Optional.empty(),
        AgentProfileType.TEAM,
        false,
        AgentProfileStatus.ACTIVE,
        4,
        AuditMetadata.createdBy(taskFixture.base.owner.id(), TaskDomainFixture.CREATED_AT));
    TaskAgentRuntimeSession taskSession = TaskAgentRuntimeSession.initializeTask(
        taskFixture.task,
        taskFixture.execution,
        taskProfile,
        taskFixture.base.executor,
        TaskPlanningFixture.POLICY_AT);
    PolicySnapshot policySnapshot = taskFixture.policy();

    AgentRuntimeSession restoredConversation = AgentRuntimeSession.reconstitute(
        conversationSession.id(),
        conversationSession.scope(),
        conversationSession.conversationId(),
        conversationSession.ownerMemberId(),
        conversationSession.ownerPrincipalId(),
        conversationSession.personalAgentPrincipalId(),
        conversationSession.agentProfileId(),
        conversationSession.agentProfileVersion(),
        conversationSession.agentScopeKey(),
        conversationSession.stateReference(),
        conversationSession.status(),
        conversationSession.version(),
        conversationSession.audit());
    TaskAgentRuntimeSession restoredTask = TaskAgentRuntimeSession.reconstitute(
        taskSession.id(),
        taskSession.scope(),
        taskSession.taskId(),
        taskSession.executionId(),
        taskSession.stepExecutionId(),
        taskSession.purpose(),
        taskSession.agentPrincipalId(),
        taskSession.agentPrincipalType(),
        taskSession.agentProfileId(),
        taskSession.agentProfileType(),
        taskSession.agentProfileVersion(),
        taskSession.agentScopeKey(),
        taskSession.stateReference(),
        taskSession.status(),
        taskSession.version(),
        taskSession.audit());
    PolicySnapshot restoredPolicy = PolicySnapshot.reconstitute(
        policySnapshot.id(),
        policySnapshot.scope(),
        policySnapshot.taskId(),
        policySnapshot.executionId(),
        policySnapshot.revision(),
        policySnapshot.parentSnapshotId(),
        policySnapshot.changeReason(),
        policySnapshot.executionPrincipal(),
        policySnapshot.policyPack(),
        policySnapshot.agentProfileId(),
        policySnapshot.agentProfileVersion(),
        policySnapshot.capabilities(),
        policySnapshot.allowedTools(),
        policySnapshot.providerBindingIds(),
        policySnapshot.budget(),
        policySnapshot.snapshotHash(),
        policySnapshot.createdByPrincipalId(),
        policySnapshot.createdAt());

    assertEquals(conversationSession.id(), restoredConversation.id());
    assertEquals(conversationSession.agentScopeKey(), restoredConversation.agentScopeKey());
    assertEquals(conversationSession.stateReference(), restoredConversation.stateReference());
    assertEquals(taskSession.id(), restoredTask.id());
    assertEquals(taskSession.agentScopeKey(), restoredTask.agentScopeKey());
    assertEquals(taskSession.agentProfileId(), restoredTask.agentProfileId());
    assertEquals(policySnapshot.id(), restoredPolicy.id());
    assertEquals(policySnapshot.snapshotHash(), restoredPolicy.snapshotHash());
    assertEquals(policySnapshot.providerBindingIds(), restoredPolicy.providerBindingIds());
    assertEquals(taskSession.agentProfileId(), restoredPolicy.agentProfileId());

    PolicySnapshotExtension extension = PolicySnapshotExtension.fromLegacy(restoredPolicy);
    assertEquals(1, extension.snapshotSchemaVersion());
    assertEquals(restoredPolicy.snapshotHash(), extension.snapshotHash());
    assertTrue(extension.templateVersion().isEmpty());
    assertTrue(extension.agentConfigurationRevision().isEmpty());
    assertTrue(extension.executionScope().isEmpty());

    PolicySnapshotExtension current = PolicySnapshotExtension.current(
        restoredPolicy, CODING, 3, ExecutionScope.TEAM);
    assertEquals(2, current.snapshotSchemaVersion());
    assertEquals(Optional.of(CODING), current.templateVersion());
    assertEquals(Optional.of(3L), current.agentConfigurationRevision());
    assertEquals(Optional.of(ExecutionScope.TEAM), current.executionScope());
    assertEquals(
        TaskFactHash.sha256(restoredPolicy.snapshotHash()
            + "|2|coding|1|3|TEAM"),
        current.snapshotHash());
  }

  private static void assertAgent(
      ProjectedAgent projected,
      AgentPair source,
      OwnershipType ownership,
      RuntimeRole role,
      TemplateVersion templateVersion) {
    assertEquals(source.profile().id(), projected.profileId());
    assertEquals(source.principal().id(), projected.principalId());
    assertEquals(source.profile().type(), projected.legacyProfileType());
    assertEquals(ownership, projected.ownershipType());
    assertEquals(role, projected.runtimeRole());
    assertEquals(templateVersion, projected.templateVersion());
  }

  private static void assertResolution(
      ResolvedBinding actual,
      ResolutionKind expectedKind,
      Optional<ConnectionCoordinate> expectedConnection) {
    assertEquals(expectedKind, actual.kind());
    assertEquals(expectedConnection, actual.connection());
    assertTrue(actual.configurationRevision() > 0);
  }

  private enum OwnershipType {
    USER,
    TEAM,
    ORGANIZATION
  }

  private enum RuntimeRole {
    PERSONAL_ASSISTANT,
    TEAM_COORDINATOR,
    SPECIALIST
  }

  private enum ExecutionScope {
    PERSONAL,
    TEAM
  }

  private enum ConnectionOwner {
    USER,
    TEAM,
    ORGANIZATION
  }

  private enum BindingKind {
    DIRECT,
    INHERIT_TEAM_DEFAULT,
    UNAVAILABLE
  }

  private enum ResolutionKind {
    DIRECT,
    INHERITED_TEAM_DEFAULT,
    ORCHESTRATION_ONLY
  }

  private record TemplateVersion(String templateKey, long version) {

    private TemplateVersion {
      if (templateKey == null || !templateKey.matches("[a-z][a-z0-9-]{0,63}")) {
        throw new IllegalArgumentException("invalid templateKey");
      }
      if (version < 1) {
        throw new IllegalArgumentException("template version must be positive");
      }
    }
  }

  private record ProjectedAgent(
      AgentProfileId profileId,
      PrincipalId principalId,
      PrincipalId legacyPrincipalOwnerId,
      Optional<TeamMemberId> ownerMemberId,
      AgentProfileType legacyProfileType,
      OwnershipType ownershipType,
      RuntimeRole runtimeRole,
      TemplateVersion templateVersion,
      boolean defaultProfile) {}

  private static final class UpgradeProjector {

    private UpgradeProjector() {}

    static ProjectedAgent projectLegacy(AgentProfile profile, Principal principal) {
      return switch (profile.type()) {
        case PERSONAL -> project(
            profile,
            principal,
            OwnershipType.USER,
            RuntimeRole.PERSONAL_ASSISTANT,
            PERSONAL_ASSISTANT);
        case TEAM -> project(
            profile,
            principal,
            OwnershipType.TEAM,
            RuntimeRole.TEAM_COORDINATOR,
            TEAM_COORDINATOR);
        // Reviewer did not exist as a durable M2-M4 template. A legacy Specialist therefore has
        // one deterministic mapping, while new Reviewer instances always carry an explicit key.
        case SPECIALIST -> project(
            profile,
            principal,
            profile.ownerMemberId().isPresent() ? OwnershipType.USER : OwnershipType.TEAM,
            RuntimeRole.SPECIALIST,
            CODING);
      };
    }

    static ProjectedAgent projectExplicit(
        AgentProfile profile, Principal principal, TemplateVersion templateVersion) {
      if (profile.type() != AgentProfileType.SPECIALIST
          || (!CODING.equals(templateVersion) && !REVIEWER.equals(templateVersion))) {
        throw new IllegalArgumentException("M5 specialist requires an approved template");
      }
      return project(
          profile,
          principal,
          profile.ownerMemberId().isPresent() ? OwnershipType.USER : OwnershipType.TEAM,
          RuntimeRole.SPECIALIST,
          templateVersion);
    }

    private static ProjectedAgent project(
        AgentProfile profile,
        Principal principal,
        OwnershipType ownershipType,
        RuntimeRole runtimeRole,
        TemplateVersion templateVersion) {
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(principal, "principal");
      PrincipalType expectedPrincipalType = switch (profile.type()) {
        case PERSONAL -> PrincipalType.PERSONAL_AGENT;
        case TEAM -> PrincipalType.TEAM_AGENT;
        case SPECIALIST -> PrincipalType.SPECIALIST_AGENT;
      };
      if (!profile.agentPrincipalId().equals(principal.id())
          || principal.type() != expectedPrincipalType
          || principal.ownerPrincipalId().isEmpty()) {
        throw new IllegalArgumentException("legacy Principal and AgentProfile do not match");
      }
      return new ProjectedAgent(
          profile.id(),
          principal.id(),
          principal.ownerPrincipalId().orElseThrow(),
          profile.ownerMemberId(),
          profile.type(),
          ownershipType,
          runtimeRole,
          templateVersion,
          profile.defaultProfile());
    }
  }

  private record ConnectionCoordinate(
      String connectionId,
      ConnectionOwner ownerType,
      Optional<PrincipalId> userOwnerPrincipalId) {

    private ConnectionCoordinate {
      if (connectionId == null || connectionId.isBlank()) {
        throw new IllegalArgumentException("connectionId must not be blank");
      }
      Objects.requireNonNull(ownerType, "ownerType");
      userOwnerPrincipalId = Objects.requireNonNull(userOwnerPrincipalId, "userOwnerPrincipalId");
      if ((ownerType == ConnectionOwner.USER) != userOwnerPrincipalId.isPresent()) {
        throw new IllegalArgumentException("USER Connection requires one USER owner");
      }
    }

    static ConnectionCoordinate user(String id, PrincipalId ownerPrincipalId) {
      return new ConnectionCoordinate(id, ConnectionOwner.USER, Optional.of(ownerPrincipalId));
    }

    static ConnectionCoordinate shared(String id, ConnectionOwner ownerType) {
      return new ConnectionCoordinate(id, ownerType, Optional.empty());
    }
  }

  private record ModelBinding(
      BindingKind kind, Optional<ConnectionCoordinate> connection) {

    private ModelBinding {
      Objects.requireNonNull(kind, "kind");
      connection = Objects.requireNonNull(connection, "connection");
      if ((kind == BindingKind.DIRECT) != connection.isPresent()) {
        throw new IllegalArgumentException("only a direct binding carries a Connection");
      }
    }

    static ModelBinding direct(ConnectionCoordinate connection) {
      return new ModelBinding(BindingKind.DIRECT, Optional.of(connection));
    }

    static ModelBinding inheritTeamDefault() {
      return new ModelBinding(BindingKind.INHERIT_TEAM_DEFAULT, Optional.empty());
    }

    static ModelBinding unavailable() {
      return new ModelBinding(BindingKind.UNAVAILABLE, Optional.empty());
    }
  }

  private record AgentConfigurationShape(
      long revision, ModelBinding personalBinding, ModelBinding teamBinding) {

    private AgentConfigurationShape {
      if (revision < 1) {
        throw new IllegalArgumentException("configuration revision must be positive");
      }
      Objects.requireNonNull(personalBinding, "personalBinding");
      Objects.requireNonNull(teamBinding, "teamBinding");
      if (personalBinding.kind() == BindingKind.INHERIT_TEAM_DEFAULT) {
        throw new IllegalArgumentException("PERSONAL binding cannot inherit a Team default");
      }
    }
  }

  private record ResolvedBinding(
      ResolutionKind kind,
      ExecutionScope executionScope,
      Optional<ConnectionCoordinate> connection,
      long configurationRevision) {}

  private static final class BindingResolver {

    private BindingResolver() {}

    static ResolvedBinding resolve(
        ProjectedAgent agent,
        AgentConfigurationShape configuration,
        ExecutionScope executionScope,
        Optional<ConnectionCoordinate> teamDefault) {
      Objects.requireNonNull(agent, "agent");
      Objects.requireNonNull(configuration, "configuration");
      Objects.requireNonNull(executionScope, "executionScope");
      Optional<ConnectionCoordinate> requiredTeamDefault =
          Objects.requireNonNull(teamDefault, "teamDefault");

      if (executionScope == ExecutionScope.TEAM
          && agent.runtimeRole() == RuntimeRole.PERSONAL_ASSISTANT) {
        return new ResolvedBinding(
            ResolutionKind.ORCHESTRATION_ONLY,
            executionScope,
            Optional.empty(),
            configuration.revision());
      }

      ModelBinding selected = executionScope == ExecutionScope.PERSONAL
          ? configuration.personalBinding()
          : configuration.teamBinding();
      ConnectionCoordinate connection;
      ResolutionKind resolutionKind;
      switch (selected.kind()) {
        case DIRECT -> {
          connection = selected.connection().orElseThrow();
          resolutionKind = ResolutionKind.DIRECT;
        }
        case INHERIT_TEAM_DEFAULT -> {
          if (executionScope != ExecutionScope.TEAM) {
            throw new IllegalStateException("only TEAM execution can inherit a Team default");
          }
          connection = requiredTeamDefault.orElseThrow(
              () -> new IllegalStateException("TEAM binding has no resolvable default"));
          resolutionKind = ResolutionKind.INHERITED_TEAM_DEFAULT;
        }
        case UNAVAILABLE -> throw new IllegalStateException(
            "Agent has no binding for " + executionScope);
        default -> throw new IllegalStateException("unknown binding kind");
      }

      if (executionScope == ExecutionScope.TEAM && connection.ownerType() == ConnectionOwner.USER) {
        throw new IllegalStateException("TEAM execution cannot use a USER Connection");
      }
      if (agent.ownershipType() != OwnershipType.USER
          && connection.ownerType() == ConnectionOwner.USER) {
        throw new IllegalStateException("shared Agent cannot use a USER Connection");
      }
      if (connection.ownerType() == ConnectionOwner.USER
          && connection.userOwnerPrincipalId()
              .filter(agent.legacyPrincipalOwnerId()::equals)
              .isEmpty()) {
        throw new IllegalStateException("USER Connection belongs to another Agent owner");
      }
      return new ResolvedBinding(
          resolutionKind,
          executionScope,
          Optional.of(connection),
          configuration.revision());
    }
  }

  /** V20 keeps a v1 Policy hash intact and hashes complete M5 coordinates into v2 snapshots. */
  private record PolicySnapshotExtension(
      int snapshotSchemaVersion,
      TaskFactHash snapshotHash,
      Optional<TemplateVersion> templateVersion,
      Optional<Long> agentConfigurationRevision,
      Optional<ExecutionScope> executionScope) {

    static PolicySnapshotExtension fromLegacy(PolicySnapshot snapshot) {
      return new PolicySnapshotExtension(
          1,
          Objects.requireNonNull(snapshot, "snapshot").snapshotHash(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    static PolicySnapshotExtension current(
        PolicySnapshot snapshot,
        TemplateVersion templateVersion,
        long configurationRevision,
        ExecutionScope executionScope) {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(templateVersion, "templateVersion");
      Objects.requireNonNull(executionScope, "executionScope");
      if (configurationRevision < 1) {
        throw new IllegalArgumentException("configuration revision must be positive");
      }
      String canonical = String.join(
          "|",
          snapshot.snapshotHash().toString(),
          "2",
          templateVersion.templateKey(),
          Long.toString(templateVersion.version()),
          Long.toString(configurationRevision),
          executionScope.name());
      return new PolicySnapshotExtension(
          2,
          TaskFactHash.sha256(canonical),
          Optional.of(templateVersion),
          Optional.of(configurationRevision),
          Optional.of(executionScope));
    }
  }

  private record AgentPair(Principal principal, AgentProfile profile) {}

  private record AgentFixture(
      Principal owner,
      TeamInitialization team,
      AgentPair defaultPersonal,
      AgentPair personalCoding,
      AgentPair personalReviewer,
      AgentPair teamCoding) {

    static AgentFixture create() {
      Principal owner = Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(ORGANIZATION_ID),
          PrincipalType.USER,
          Optional.empty(),
          "M5 owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          CREATED_AT);
      TeamInitialization team = TeamInitialization.create(owner, "M5 team", CREATED_AT);
      AgentFixture base = new AgentFixture(owner, team, null, null, null, null);
      AgentPair defaultPersonal = new AgentPair(
          team.ownerPersonalAgent().agentPrincipal(), team.ownerPersonalAgent().agentProfile());
      AgentPair personalCoding =
          base.specialist("Java Coding", Optional.of(team.ownerMember().id()));
      AgentPair personalReviewer =
          base.specialist("Reviewer", Optional.of(team.ownerMember().id()));
      AgentPair teamCoding = base.specialist("Team Coding", Optional.empty());
      return new AgentFixture(
          owner, team, defaultPersonal, personalCoding, personalReviewer, teamCoding);
    }

    AgentPair specialist(String displayName, Optional<TeamMemberId> ownerMemberId) {
      Principal principal = Principal.create(
          PrincipalId.generate(),
          PrincipalScope.team(ORGANIZATION_ID, team.team().id()),
          PrincipalType.SPECIALIST_AGENT,
          Optional.of(owner.id()),
          displayName,
          Optional.empty(),
          ownerMemberId.isPresent() ? PrincipalVisibility.PRIVATE : PrincipalVisibility.TEAM,
          CREATED_AT);
      return pair(principal, AgentProfileType.SPECIALIST, ownerMemberId);
    }

    AgentPair teamCoordinator(String displayName) {
      Principal principal = Principal.create(
          PrincipalId.generate(),
          PrincipalScope.team(ORGANIZATION_ID, team.team().id()),
          PrincipalType.TEAM_AGENT,
          Optional.of(owner.id()),
          displayName,
          Optional.empty(),
          PrincipalVisibility.TEAM,
          CREATED_AT);
      return pair(principal, AgentProfileType.TEAM, Optional.empty());
    }

    private AgentPair pair(
        Principal principal,
        AgentProfileType profileType,
        Optional<TeamMemberId> ownerMemberId) {
      AgentProfile profile = AgentProfile.reconstitute(
          AgentProfileId.generate(),
          WorkspaceScope.team(ORGANIZATION_ID, team.team().id()),
          team.defaultWorkspace().id(),
          principal.id(),
          ownerMemberId,
          profileType,
          false,
          AgentProfileStatus.ACTIVE,
          0,
          AuditMetadata.createdBy(owner.id(), CREATED_AT));
      return new AgentPair(principal, profile);
    }
  }
}

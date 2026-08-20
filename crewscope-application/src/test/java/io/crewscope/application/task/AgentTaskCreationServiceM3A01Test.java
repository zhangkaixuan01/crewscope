package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.CreateCodingTargetCommand;
import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.conversation.ReadableConversationMessage;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.task.event.TaskDelegatedToAgent;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Application contract for the M3-A01 atomic "delegate to Agent" command. */
class AgentTaskCreationServiceM3A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T06:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, workspaceId, projectId);
    private final Principal owner = user("Owner");
    private final Principal executor = personalAgent(owner);
    private final WorkItem workItem = WorkItem.reconstitute(
            WorkItemId.generate(),
            scope,
            new WorkItemKey("CRW-301"),
            "Durable Task API",
            WorkItemStatus.READY,
            7,
            AuditMetadata.createdBy(owner.id(), NOW));
    private final ResponsibilityAssignment ownerAssignment = assignment(
            ResponsibilityRole.OWNER, owner, Optional.of(TeamMemberId.generate()));
    private final ResponsibilityAssignment executorAssignment = assignment(
            ResponsibilityRole.EXECUTOR, executor, Optional.empty());
    private final AgentProfile profile = AgentProfile.reconstitute(
            AgentProfileId.generate(),
            WorkspaceScope.team(organizationId, teamId),
            workspaceId,
            executor.id(),
            Optional.of(TeamMemberId.generate()),
            AgentProfileType.PERSONAL,
            true,
            AgentProfileStatus.ACTIVE,
            3,
            AuditMetadata.createdBy(owner.id(), NOW));

    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final ConversationApplicationService conversations =
            mock(ConversationApplicationService.class);
    private final ProviderBindingResolver bindings = mock(ProviderBindingResolver.class);
    private final RepositoryBindingRepository repositoryBindings =
            mock(RepositoryBindingRepository.class);
    private final RepositoryBindingPreflightPort repositoryPreflight =
            mock(RepositoryBindingPreflightPort.class);
    private final BuildProfileCatalog buildProfiles = mock(BuildProfileCatalog.class);
    private final CodingTargetSnapshotRepository codingTargets =
            mock(CodingTargetSnapshotRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
    private final SafetyEnforcementOverlayRepository overlays =
            mock(SafetyEnforcementOverlayRepository.class);
    private final ConversationTaskLinkRepository links = mock(ConversationTaskLinkRepository.class);
    private final DomainEventStore events = mock(DomainEventStore.class);
    private final ConversationEventRepository conversationEvents =
            mock(ConversationEventRepository.class);
    private final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };
    private final TaskCreationPolicySpec policySpec = new TaskCreationPolicySpec(
            new PolicyPackReference(PolicyPackId.generate(), 1),
            Set.of(ExecutionCapability.PLAN),
            Set.of("fixture.execute"),
            new PolicyBudget(10_000, 8, 16, 300),
            3,
            TaskExecutionPriority.NORMAL);

    private AgentTaskCreationService service;

    @BeforeEach
    void setUp() {
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(workItems.findById(organizationId, workItem.id())).thenReturn(Optional.of(workItem));
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, executorAssignment));
        when(profiles.findById(organizationId, profile.id())).thenReturn(Optional.of(profile));
        when(principals.findById(organizationId, executor.id())).thenReturn(Optional.of(executor));
        when(tasks.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policies.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(overlays.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AgentTaskCreationService(
                accessPolicy,
                workItems,
                assignments,
                principals,
                profiles,
                conversations,
                bindings,
                repositoryBindings,
                repositoryPreflight,
                buildProfiles,
                codingTargets,
                tasks,
                executions,
                policies,
                overlays,
                links,
                events,
                conversationEvents,
                taskEvents,
                outbox,
                receipts,
                transactions,
                () -> NOW,
                policySpec);
    }

    @Test
    void ownerAtomicallyCreatesTheCompleteSchedulerVisibleGraph() {
        CommandExecution<AgentTaskCreationResult> execution = service.create(
                context(owner, "delegate-agent-1"),
                teamId,
                projectId,
                workItem.id(),
                command());

        AgentTaskCreationResult result = execution.result().orElseThrow();
        assertFalse(execution.replayed());
        assertEquals(TaskStatus.ACTIVE, result.task().status());
        assertEquals(TaskExecutionStatus.READY, result.execution().status());
        assertEquals(command().brief(), result.task().brief());
        assertEquals(executor.id(), result.policySnapshot().executionPrincipal().principalId());
        assertEquals(profile.id(), result.policySnapshot().agentProfileId());
        assertEquals(result.execution().id(), result.task().currentExecutionId().orElseThrow());

        ArgumentCaptor<DomainEventEnvelope<?>> eventCaptor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events).append(eventCaptor.capture());
        verify(taskEvents).append(any(), eq(eventCaptor.getValue()));
        TaskDelegatedToAgent payload = (TaskDelegatedToAgent) eventCaptor.getValue().payload();
        assertEquals(command().brief().objective(), payload.objective());
        assertEquals(command().brief().contentHash().value(), payload.briefHash());
        assertEquals(executorAssignment.id().value(), payload.executorAssignmentId());
        assertEquals("ACTIVE", payload.taskStatus());
        assertEquals("READY", payload.executionStatus());

        InOrder order = inOrder(
                assignments, tasks, executions, policies, overlays,
                events, taskEvents, outbox, receipts);
        order.verify(assignments).lockResponsibilityChain(organizationId, workItem.id());
        order.verify(tasks).create(any());
        order.verify(executions).create(any());
        order.verify(policies).create(any());
        order.verify(overlays).create(any());
        order.verify(executions, times(2)).update(any());
        order.verify(tasks).update(any());
        order.verify(events).append(any());
        order.verify(taskEvents).append(any(), any());
        order.verify(outbox).enqueue(any());
        order.verify(receipts).complete(any(), any(), any(), any());
        verify(links, never()).create(any());
        verifyNoInteractions(conversationEvents);
    }

    @Test
    void executorUserAlsoHasObjectLevelDelegationAuthority() {
        Principal humanExecutor = user("Human executor");
        ResponsibilityAssignment humanAssignment = assignment(
                ResponsibilityRole.EXECUTOR, humanExecutor, Optional.of(TeamMemberId.generate()));
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, humanAssignment, executorAssignment));

        CommandExecution<AgentTaskCreationResult> execution = service.create(
                context(humanExecutor, "delegate-agent-2"),
                teamId,
                projectId,
                workItem.id(),
                command());

        assertEquals(TaskExecutionStatus.READY,
                execution.result().orElseThrow().execution().status());
    }

    @Test
    void idempotentReplayReturnsTheOriginalReceiptWithoutNewFacts() {
        CommandReceipt original = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(original));

        CommandExecution<AgentTaskCreationResult> replay = service.create(
                context(owner, "delegate-agent-replay"),
                teamId,
                projectId,
                workItem.id(),
                command());

        assertTrue(replay.replayed());
        assertEquals(original, replay.receipt());
        verifyNoInteractions(accessPolicy, assignments, profiles, principals, tasks, executions,
                policies, overlays, links, events, conversationEvents, outbox);
        verify(receipts, never()).complete(any(), any(), any(), any());
    }

    @Test
    void rejectsStaleWorkItemVersionAfterTakingTheResponsibilityLock() {
        CreateAgentTaskCommand stale = new CreateAgentTaskCommand(
                command().brief(), profile.id(), Optional.empty(), Set.of(), 6);

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.create(
                        context(owner, "delegate-agent-stale"),
                        teamId,
                        projectId,
                        workItem.id(),
                        stale));

        verify(assignments).lockResponsibilityChain(organizationId, workItem.id());
        verifyNoInteractions(tasks, executions, policies, overlays, events, outbox);
    }

    @Test
    void rejectsAVisibleUserWhoIsNeitherCurrentOwnerNorExecutor() {
        Principal observer = user("Observer");

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(
                        context(observer, "delegate-agent-observer"),
                        teamId,
                        projectId,
                        workItem.id(),
                        command()));

        verifyNoInteractions(tasks, executions, policies, overlays, events, outbox);
    }

    @Test
    void rejectsASpecialistProfileAsTheTaskLevelOrchestrator() {
        Principal specialist = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(owner.id()),
                "Coding specialist",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        AgentProfile specialistProfile = AgentProfile.reconstitute(
                AgentProfileId.generate(),
                WorkspaceScope.team(organizationId, teamId),
                workspaceId,
                specialist.id(),
                Optional.empty(),
                AgentProfileType.SPECIALIST,
                false,
                AgentProfileStatus.ACTIVE,
                1,
                AuditMetadata.createdBy(owner.id(), NOW));
        ResponsibilityAssignment specialistAssignment = assignment(
                ResponsibilityRole.EXECUTOR, specialist, Optional.empty());
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, specialistAssignment));
        when(profiles.findById(organizationId, specialistProfile.id()))
                .thenReturn(Optional.of(specialistProfile));
        when(principals.findById(organizationId, specialist.id()))
                .thenReturn(Optional.of(specialist));
        CreateAgentTaskCommand specialistCommand = new CreateAgentTaskCommand(
                command().brief(), specialistProfile.id(), Optional.empty(), Set.of(), 7);

        assertThrows(
                DomainValidationException.class,
                () -> service.create(
                        context(owner, "delegate-agent-specialist"),
                        teamId,
                        projectId,
                        workItem.id(),
                        specialistCommand));

        verifyNoInteractions(tasks, executions, policies, overlays, events, outbox);
    }

    @Test
    void rejectsWhenTheSelectedAgentIsNotACurrentExecutor() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, assignment(
                        ResponsibilityRole.EXECUTOR,
                        user("Human executor"),
                        Optional.of(TeamMemberId.generate()))));

        assertThrows(
                DomainValidationException.class,
                () -> service.create(
                        context(owner, "delegate-agent-not-executor"),
                        teamId,
                        projectId,
                        workItem.id(),
                        command()));

        verifyNoInteractions(tasks, executions, policies, overlays, events, outbox);
    }

    @Test
    void rejectsARequestedProviderBindingThatIsNoLongerCurrent() {
        ProviderBindingId bindingId = ProviderBindingId.generate();
        CreateAgentTaskCommand withBinding = new CreateAgentTaskCommand(
                command().brief(), profile.id(), Optional.empty(), Set.of(bindingId), 7);
        when(bindings.resolveCurrent(organizationId, bindingId)).thenReturn(Optional.empty());

        assertThrows(
                DomainValidationException.class,
                () -> service.create(
                        context(owner, "delegate-agent-stale-binding"),
                        teamId,
                        projectId,
                        workItem.id(),
                        withBinding));

        verifyNoInteractions(tasks, executions, policies, overlays, events, outbox);
    }

    @Test
    void requestHashChangesWithTheApprovedTaskBrief() {
        service.create(
                context(owner, "delegate-agent-same-key"),
                teamId,
                projectId,
                workItem.id(),
                command());
        CreateAgentTaskCommand revised = new CreateAgentTaskCommand(
                new TaskBrief("Revised objective", List.of("Revised criterion")),
                profile.id(),
                Optional.empty(),
                Set.of(),
                7);
        service.create(
                context(owner, "delegate-agent-same-key"),
                teamId,
                projectId,
                workItem.id(),
                revised);

        ArgumentCaptor<CommandReservationRequest> reservations =
                ArgumentCaptor.forClass(CommandReservationRequest.class);
        verify(receipts, times(2)).reserve(reservations.capture());
        assertEquals(reservations.getAllValues().get(0).idempotencyKey(),
                reservations.getAllValues().get(1).idempotencyKey());
        assertFalse(reservations.getAllValues().get(0).requestHash()
                .equals(reservations.getAllValues().get(1).requestHash()));
    }

    @Test
    void codingDelegationCapturesTheApprovedTargetBeforePublishingTheFirstAttempt() {
        RepositoryBinding repositoryBinding = activeRepositoryBinding();
        BuildProfile buildProfile = buildProfile();
        CreateAgentTaskCommand codingCommand = codingCommand(repositoryBinding, buildProfile);
        when(repositoryBindings.findById(
                        organizationId, teamId, projectId, repositoryBinding.id()))
                .thenReturn(Optional.of(repositoryBinding));
        when(buildProfiles.findExact(buildProfile.reference()))
                .thenReturn(Optional.of(buildProfile));
        when(repositoryPreflight.preflight(
                        repositoryBinding, new RepositoryBranchName("main")))
                .thenReturn(new RepositoryBindingPreflightResult(
                        repositoryBinding.repositoryKey(),
                        new RepositoryBranchName("main"),
                        new RepositoryCommitId("a".repeat(40))));
        when(codingTargets.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommandExecution<AgentTaskCreationResult> execution = service.create(
                context(owner, "delegate-coding-1"),
                teamId,
                projectId,
                workItem.id(),
                codingCommand);

        ArgumentCaptor<CodingTargetSnapshot> snapshot =
                ArgumentCaptor.forClass(CodingTargetSnapshot.class);
        verify(codingTargets).create(snapshot.capture());
        assertEquals(execution.result().orElseThrow().task().id(), snapshot.getValue().taskId());
        assertEquals(repositoryBinding.id(), snapshot.getValue().repositoryBindingId());
        assertEquals(repositoryBinding.version(), snapshot.getValue().repositoryBindingVersion());
        assertEquals("main", snapshot.getValue().baselineRef().value());
        assertEquals("a".repeat(40), snapshot.getValue().baselineCommit().value());
        assertEquals(List.of("crewscope-application/src"),
                snapshot.getValue().allowedPaths().values());
        assertEquals(buildProfile.reference(), snapshot.getValue().buildProfile());
        assertEquals(codingCommand.brief().acceptanceCriteria(),
                snapshot.getValue().acceptanceCriteria());

        InOrder order = inOrder(tasks, codingTargets, executions, policies, overlays);
        order.verify(tasks).create(any());
        order.verify(codingTargets).create(any());
        order.verify(executions).create(any());
        order.verify(policies).create(any());
        order.verify(overlays).create(any());
    }

    @Test
    void rejectsCrossScopeOrDisabledRepositoryBindingsBeforeCreatingAnyTaskFact() {
        RepositoryBinding repositoryBinding = activeRepositoryBinding();
        CreateAgentTaskCommand codingCommand = codingCommand(repositoryBinding, buildProfile());
        when(repositoryBindings.findById(
                        organizationId, teamId, projectId, repositoryBinding.id()))
                .thenReturn(Optional.empty());

        assertThrows(DomainValidationException.class, () -> service.create(
                context(owner, "delegate-coding-cross-scope"),
                teamId,
                projectId,
                workItem.id(),
                codingCommand));

        RepositoryBinding disabled = RepositoryBinding.reconstitute(
                repositoryBinding.id(),
                repositoryBinding.scope(),
                repositoryBinding.kind(),
                repositoryBinding.repositoryKey(),
                repositoryBinding.defaultBranch(),
                RepositoryBindingStatus.DISABLED,
                repositoryBinding.version() + 1,
                repositoryBinding.audit());
        when(repositoryBindings.findById(
                        organizationId, teamId, projectId, repositoryBinding.id()))
                .thenReturn(Optional.of(disabled));
        assertThrows(DomainValidationException.class, () -> service.create(
                context(owner, "delegate-coding-disabled"),
                teamId,
                projectId,
                workItem.id(),
                codingCommand));

        verifyNoInteractions(repositoryPreflight, codingTargets, tasks, executions, policies, overlays);
    }

    @Test
    void rejectsUnknownBuildProfileAndInvalidRefBeforeCreatingAnyTaskFact() {
        RepositoryBinding repositoryBinding = activeRepositoryBinding();
        BuildProfile buildProfile = buildProfile();
        CreateAgentTaskCommand codingCommand = codingCommand(repositoryBinding, buildProfile);
        when(repositoryBindings.findById(
                        organizationId, teamId, projectId, repositoryBinding.id()))
                .thenReturn(Optional.of(repositoryBinding));
        when(buildProfiles.findExact(buildProfile.reference()))
                .thenReturn(Optional.empty());

        assertThrows(DomainValidationException.class, () -> service.create(
                context(owner, "delegate-coding-profile"),
                teamId,
                projectId,
                workItem.id(),
                codingCommand));
        verifyNoInteractions(repositoryPreflight, codingTargets, tasks, executions, policies, overlays);

        when(buildProfiles.findExact(buildProfile.reference()))
                .thenReturn(Optional.of(buildProfile));
        when(repositoryPreflight.preflight(repositoryBinding, new RepositoryBranchName("main")))
                .thenThrow(new RepositoryBindingPreflightException(
                        RepositoryBindingPreflightError.REFERENCE_INVALID,
                        "Managed repository Preflight failed"));
        assertThrows(RepositoryBindingPreflightException.class, () -> service.create(
                context(owner, "delegate-coding-ref"),
                teamId,
                projectId,
                workItem.id(),
                codingCommand));
        verifyNoInteractions(codingTargets, tasks, executions, policies, overlays);
    }

    @Test
    void requestHashBindsAllCodingTargetFacts() {
        RepositoryBinding repositoryBinding = activeRepositoryBinding();
        BuildProfile buildProfile = buildProfile();
        CreateAgentTaskCommand first = codingCommand(repositoryBinding, buildProfile);
        CreateAgentTaskCommand second = new CreateAgentTaskCommand(
                first.brief(),
                first.executorAgentProfileId(),
                first.conversationSource(),
                first.providerBindingIds(),
                Optional.of(new CreateCodingTargetCommand(
                        repositoryBinding.id(),
                        new RepositoryBranchName("main"),
                        CodingTargetAllowedPaths.of("crewscope-server/src"),
                        buildProfile.reference())),
                first.expectedWorkItemVersion());
        when(repositoryBindings.findById(
                        organizationId, teamId, projectId, repositoryBinding.id()))
                .thenReturn(Optional.of(repositoryBinding));
        when(buildProfiles.findExact(buildProfile.reference()))
                .thenReturn(Optional.of(buildProfile));
        when(repositoryPreflight.preflight(repositoryBinding, new RepositoryBranchName("main")))
                .thenReturn(new RepositoryBindingPreflightResult(
                        repositoryBinding.repositoryKey(),
                        new RepositoryBranchName("main"),
                        new RepositoryCommitId("a".repeat(40))));

        service.create(context(owner, "delegate-coding-same-key"), teamId, projectId, workItem.id(), first);
        service.create(context(owner, "delegate-coding-same-key"), teamId, projectId, workItem.id(), second);

        ArgumentCaptor<CommandReservationRequest> reservations =
                ArgumentCaptor.forClass(CommandReservationRequest.class);
        verify(receipts, times(2)).reserve(reservations.capture());
        assertFalse(reservations.getAllValues().get(0).requestHash()
                .equals(reservations.getAllValues().get(1).requestHash()));
    }

    @Test
    void controlAndConversationDelegationPersistTheSameCodingTargetFactShape() {
        RepositoryBinding repositoryBinding = activeRepositoryBinding();
        BuildProfile buildProfile = buildProfile();
        CreateAgentTaskCommand control = codingCommand(repositoryBinding, buildProfile);
        ConversationScope conversationScope =
                new ConversationScope(organizationId, teamId, workspaceId);
        Conversation conversation = Conversation.reconstitute(
                ConversationId.generate(),
                conversationScope,
                TeamMemberId.generate(),
                owner.id(),
                executor.id(),
                "Coding request",
                ConversationVisibility.PRIVATE,
                ConversationStatus.ACTIVE,
                Optional.of(new MessageSequence(1)),
                1,
                AuditMetadata.createdBy(owner.id(), NOW));
        Message message = Message.reconstitute(
                MessageId.generate(),
                conversationScope,
                conversation.id(),
                new MessageSequence(1),
                MessageType.SYSTEM_NOTICE,
                Optional.empty(),
                Optional.empty(),
                new MessageContent("Confirmed Coding target"),
                AuditMetadata.createdBy(owner.id(), NOW));
        CreateAgentTaskCommand conversational = new CreateAgentTaskCommand(
                control.brief(),
                control.executorAgentProfileId(),
                Optional.of(new TaskConversationSource(conversation.id(), message.id())),
                control.providerBindingIds(),
                control.codingTarget(),
                control.expectedWorkItemVersion());
        when(conversations.requireReadableMessage(
                        any(), eq(organizationId), eq(teamId), eq(conversation.id()), eq(message.id())))
                .thenReturn(new ReadableConversationMessage(conversation, message));
        when(repositoryBindings.findById(
                        organizationId, teamId, projectId, repositoryBinding.id()))
                .thenReturn(Optional.of(repositoryBinding));
        when(buildProfiles.findExact(buildProfile.reference()))
                .thenReturn(Optional.of(buildProfile));
        when(repositoryPreflight.preflight(repositoryBinding, new RepositoryBranchName("main")))
                .thenReturn(new RepositoryBindingPreflightResult(
                        repositoryBinding.repositoryKey(),
                        new RepositoryBranchName("main"),
                        new RepositoryCommitId("a".repeat(40))));
        when(codingTargets.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(context(owner, "delegate-coding-control"),
                teamId, projectId, workItem.id(), control);
        service.create(context(owner, "delegate-coding-conversation"),
                teamId, projectId, workItem.id(), conversational);

        ArgumentCaptor<CodingTargetSnapshot> snapshots =
                ArgumentCaptor.forClass(CodingTargetSnapshot.class);
        verify(codingTargets, times(2)).create(snapshots.capture());
        CodingTargetSnapshot controlSnapshot = snapshots.getAllValues().get(0);
        CodingTargetSnapshot conversationSnapshot = snapshots.getAllValues().get(1);
        assertEquals(controlSnapshot.repositoryBindingId(),
                conversationSnapshot.repositoryBindingId());
        assertEquals(controlSnapshot.repositoryBindingVersion(),
                conversationSnapshot.repositoryBindingVersion());
        assertEquals(controlSnapshot.baselineRef(), conversationSnapshot.baselineRef());
        assertEquals(controlSnapshot.baselineCommit(), conversationSnapshot.baselineCommit());
        assertEquals(controlSnapshot.allowedPaths(), conversationSnapshot.allowedPaths());
        assertEquals(controlSnapshot.buildProfile(), conversationSnapshot.buildProfile());
        assertEquals(controlSnapshot.acceptanceCriteria(),
                conversationSnapshot.acceptanceCriteria());
        verify(links).create(any());
        verify(conversationEvents).append(eq(conversation.id()), any());
    }

    private CreateAgentTaskCommand command() {
        return new CreateAgentTaskCommand(
                new TaskBrief(
                        "Create the durable Task API",
                        List.of("Create one READY execution", "Publish auditable facts")),
                profile.id(),
                Optional.empty(),
                Set.of(),
                workItem.version());
    }

    private CreateAgentTaskCommand codingCommand(
            RepositoryBinding repositoryBinding, BuildProfile buildProfile) {
        CreateAgentTaskCommand base = command();
        return new CreateAgentTaskCommand(
                base.brief(),
                base.executorAgentProfileId(),
                base.conversationSource(),
                base.providerBindingIds(),
                Optional.of(new CreateCodingTargetCommand(
                        repositoryBinding.id(),
                        new RepositoryBranchName("main"),
                        CodingTargetAllowedPaths.of("crewscope-application/src"),
                        buildProfile.reference())),
                base.expectedWorkItemVersion());
    }

    private RepositoryBinding activeRepositoryBinding() {
        return RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                new RepositoryBindingScope(organizationId, teamId, workspaceId, projectId),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey("crewscope-java"),
                new RepositoryBranchName("main"),
                RepositoryBindingStatus.ACTIVE,
                2,
                AuditMetadata.createdBy(owner.id(), NOW));
    }

    private BuildProfile buildProfile() {
        return BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN,
                17,
                new SandboxImageReference("maven@sha256:" + "b".repeat(64)),
                CommandCatalog.of(
                        CommandKind.TEST,
                        new BuildCommand(
                                "coding.maven.test",
                                List.of("mvn", "test"),
                                ".",
                                60,
                                900)));
    }

    private TeamCommandContext context(Principal actor, String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                new io.crewscope.application.command.IdempotencyKey(key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private Principal user(String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }

    private Principal personalAgent(Principal ownerPrincipal) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(ownerPrincipal.id()),
                "Owner agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                NOW);
    }

    private ResponsibilityAssignment assignment(
            ResponsibilityRole role, Principal actor, Optional<TeamMemberId> memberId) {
        return ResponsibilityAssignment.reconstitute(
                ResponsibilityAssignmentId.generate(),
                scope,
                workItem.id(),
                role,
                actor.id(),
                actor.type(),
                memberId,
                ResponsibilityAssignmentStatus.ACTIVE,
                owner.id(),
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(owner.id(), NOW));
    }
}

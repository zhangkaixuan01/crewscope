package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactEncryption;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactKind;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Artifact integrity, Exit Code and immutable persistence coverage for M4-I07 evidence. */
class CommandEvidenceWriterM4I07Test {

    private static final Instant NOW = Instant.parse("2026-08-18T15:00:00Z");

    private final ArtifactStore artifactStore = mock(ArtifactStore.class);
    private final CommandEvidenceRepository repository = mock(CommandEvidenceRepository.class);
    private final AtomicReference<String> storedLog = new AtomicReference<>();
    private Facts facts;

    @BeforeEach
    void setUp() {
        facts = facts();
        when(artifactStore.put(any(), any())).thenAnswer(invocation -> {
            ArtifactWriteRequest request = invocation.getArgument(0);
            java.io.InputStream content = invocation.getArgument(1);
            byte[] bytes = content.readAllBytes();
            storedLog.set(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(request.declaredSize(), bytes.length);
            assertEquals(request.expectedHash(), io.crewscope.application.artifact.Sha256Hash.digest(bytes));
            return new ArtifactDescriptor(
                    request.artifactId(),
                    request.scope(),
                    request.contentType(),
                    request.declaredSize(),
                    request.expectedHash(),
                    request.dataClassification(),
                    request.visibility(),
                    URI.create("memory:/" + request.artifactId()),
                    ArtifactEncryption.NONE,
                    request.producer(),
                    UtcTimestamp.from(NOW),
                    request.retentionUntil(UtcTimestamp.from(NOW)),
                    Optional.empty());
        });
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void writesRestrictedWorkspaceLogThenPersistsHashClosedEvidence() {
        SandboxCommandExecution execution = new SandboxCommandExecution(
                facts.spec(),
                UtcTimestamp.from(NOW.minusSeconds(2)),
                UtcTimestamp.from(NOW.minusSeconds(1)),
                CommandTermination.EXITED,
                Optional.of(9),
                "compile output\n",
                "compile failed\n",
                false);
        CommandEvidenceWriter writer = new CommandEvidenceWriter(
                repository,
                new CommandLogArtifactWriter(artifactStore),
                Clock.fixed(NOW, ZoneOffset.UTC));

        CommandEvidence evidence = writer.write(
                facts.workspace(),
                facts.policy(),
                facts.actor(),
                EvidenceSequence.first(),
                execution);

        assertEquals(CommandTermination.EXITED, evidence.termination());
        assertEquals(9, evidence.exitCode().orElseThrow());
        assertFalse(evidence.succeeded());
        assertEquals(ArtifactDataClassification.RESTRICTED, capturedRequest().dataClassification());
        assertEquals(ArtifactVisibility.WORKSPACE, capturedRequest().visibility());
        assertEquals(facts.scope().workspaceId(), capturedRequest().scope().workspaceId().orElseThrow());
        assertTrue(storedLog.get().contains("crewscope-command-log-v1"));
        assertTrue(storedLog.get().contains("compile failed"));
        assertEquals(
                capturedRequest().expectedHash().toString(),
                evidence.commandLog().contentHash().toString());
    }

    @Test
    void hidesArtifactFailuresAndRawOutputFromTheExceptionBoundary() {
        org.mockito.Mockito.reset(artifactStore);
        when(artifactStore.put(any(), any()))
                .thenThrow(new IllegalStateException("/host/secret and raw build output"));
        CommandEvidenceWriter writer = new CommandEvidenceWriter(
                repository,
                new CommandLogArtifactWriter(artifactStore),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SandboxCommandExecution execution = new SandboxCommandExecution(
                facts.spec(),
                UtcTimestamp.from(NOW.minusSeconds(1)),
                UtcTimestamp.from(NOW),
                CommandTermination.START_FAILED,
                Optional.empty(),
                "secret stdout",
                "secret stderr",
                false);

        SandboxCommandException failure = assertThrows(
                SandboxCommandException.class,
                () -> writer.write(
                        facts.workspace(),
                        facts.policy(),
                        facts.actor(),
                        EvidenceSequence.first(),
                        execution));

        assertEquals(SandboxCommandError.EVIDENCE_PUBLICATION_FAILED, failure.error());
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
    }

    @Test
    void registersCommandLogRuntimeMetadataBeforeEvidencePublication() {
        RuntimeArtifactRepository runtimeArtifacts = mock(RuntimeArtifactRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentRun run = mock(AgentRun.class);
        TaskId taskId = facts.workspace().taskId();
        TaskExecutionId executionId = facts.workspace().taskExecutionId();
        PrincipalId actorId = facts.actor().id();
        when(run.id()).thenReturn(AgentRunId.generate());
        when(run.scope()).thenReturn(facts.scope());
        when(run.taskId()).thenReturn(taskId);
        when(run.executionId()).thenReturn(executionId);
        when(run.stepExecutionId()).thenReturn(Optional.empty());
        when(run.agentPrincipalId()).thenReturn(actorId);
        when(run.runSequence()).thenReturn(2L);
        when(runs.findByExecution(
                        facts.scope().organizationId(), executionId))
                .thenReturn(List.of(run));
        when(runtimeArtifacts.findByArtifactId(any(), any())).thenReturn(Optional.empty());
        when(runtimeArtifacts.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CodingRuntimeArtifactRegistrar registrar = new CodingRuntimeArtifactRegistrar(
                runtimeArtifacts, runs);
        CommandEvidenceWriter writer = new CommandEvidenceWriter(
                repository,
                new CommandLogArtifactWriter(
                        new CodingArtifactPublisher(
                                artifactStore, new CodingArtifactProperties()),
                        registrar),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SandboxCommandExecution execution = new SandboxCommandExecution(
                facts.spec(),
                UtcTimestamp.from(NOW.minusSeconds(2)),
                UtcTimestamp.from(NOW.minusSeconds(1)),
                CommandTermination.EXITED,
                Optional.of(0),
                "BUILD SUCCESS\n",
                "",
                false);

        writer.write(
                facts.workspace(),
                facts.policy(),
                facts.actor(),
                EvidenceSequence.first(),
                execution);

        org.mockito.ArgumentCaptor<RuntimeArtifact> captured =
                org.mockito.ArgumentCaptor.forClass(RuntimeArtifact.class);
        org.mockito.Mockito.verify(runtimeArtifacts).create(captured.capture());
        assertEquals(RuntimeArtifactKind.COMMAND_LOG, captured.getValue().kind());
        assertEquals(run.id(), captured.getValue().agentRunId());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(runtimeArtifacts, repository);
        order.verify(runtimeArtifacts).create(any());
        order.verify(repository).create(any());
    }

    private ArtifactWriteRequest capturedRequest() {
        org.mockito.ArgumentCaptor<ArtifactWriteRequest> request =
                org.mockito.ArgumentCaptor.forClass(ArtifactWriteRequest.class);
        try {
            org.mockito.Mockito.verify(artifactStore).put(request.capture(), any());
        } catch (RuntimeException failure) {
            throw failure;
        }
        return request.getValue();
    }

    private static Facts facts() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkItemScope scope = new WorkItemScope(
                organizationId,
                teamId,
                WorkspaceId.generate(),
                WorkProjectId.generate());
        Principal actor = mock(Principal.class);
        when(actor.id()).thenReturn(PrincipalId.generate());
        when(actor.scope()).thenReturn(PrincipalScope.team(organizationId, teamId));
        when(actor.canAct()).thenReturn(true);

        BuildCommand command = new BuildCommand(
                "command.test", List.of("./mvnw", "test"), ".", 30, 120);
        BuildProfile profile = BuildProfile.define(
                "m4-i07-profile",
                1,
                BuildTool.MAVEN_WRAPPER,
                17,
                new SandboxImageReference("maven@sha256:" + "a".repeat(64)),
                new CommandCatalog(Map.of(CommandKind.TEST, command)));
        WorkspacePolicyReference policyReference = new WorkspacePolicyReference(
                WorkspacePolicyId.generate(), TaskFactHash.sha256("policy"));
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        when(policy.reference()).thenReturn(policyReference);
        when(policy.policyHash()).thenReturn(policyReference.policyHash());
        when(policy.buildProfile()).thenReturn(profile.reference());
        when(policy.commandCatalog()).thenReturn(profile.commandCatalog());
        when(policy.sandboxBudget()).thenReturn(new SandboxResourceBudget(
                SandboxNetworkMode.NONE, 1, 256, 32, 120, 65_536, true));

        CommandSpec spec = CommandSpec.capture(
                policy, profile, CommandKind.TEST, command.argv(), 30);
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.ACTIVE);
        when(workspace.scope()).thenReturn(scope);
        when(workspace.taskId()).thenReturn(taskId);
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.attempt()).thenReturn(1);
        when(workspace.id()).thenReturn(ExecutionWorkspaceId.generate());
        when(workspace.fingerprint()).thenReturn(new ExecutionWorkspaceFingerprint(
                TaskFactHash.sha256("workspace").value()));
        when(workspace.codingTarget()).thenReturn(target);
        when(policy.scope()).thenReturn(scope);
        when(policy.taskId()).thenReturn(taskId);
        when(policy.taskExecutionId()).thenReturn(executionId);
        when(policy.attempt()).thenReturn(1);
        when(policy.codingTarget()).thenReturn(target);
        return new Facts(scope, actor, profile, policy, workspace, spec);
    }

    private record Facts(
            WorkItemScope scope,
            Principal actor,
            BuildProfile profile,
            WorkspacePolicy policy,
            ExecutionWorkspace workspace,
            CommandSpec spec) {}
}

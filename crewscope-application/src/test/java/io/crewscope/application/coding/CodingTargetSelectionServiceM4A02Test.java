package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
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
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Member authorization and public option contract for the M4-A02 CodingTarget form. */
class CodingTargetSelectionServiceM4A02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-19T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            NOW);
    private final TeamAccessContext access = new TeamAccessContext(actor, false);
    private final WorkItem workItem = WorkItem.reconstitute(
            WorkItemId.generate(),
            new WorkItemScope(organizationId, teamId, workspaceId, projectId),
            new WorkItemKey("CRW-402"),
            "Coding target",
            WorkItemStatus.READY,
            1,
            AuditMetadata.createdBy(actor.id(), NOW));
    private final RepositoryBinding binding = RepositoryBinding.reconstitute(
            RepositoryBindingId.generate(),
            new RepositoryBindingScope(organizationId, teamId, workspaceId, projectId),
            RepositoryKind.LOCAL_MANAGED,
            new RepositoryKey("crewscope-java"),
            new RepositoryBranchName("main"),
            RepositoryBindingStatus.ACTIVE,
            1,
            AuditMetadata.createdBy(actor.id(), NOW));

    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final RepositoryBindingRepository bindings = mock(RepositoryBindingRepository.class);
    private final RepositoryBindingPreflightPort preflight =
            mock(RepositoryBindingPreflightPort.class);
    private final BuildProfile profile = profile();
    private final BuildProfileCatalog profiles = new ImmutableBuildProfileCatalog(List.of(profile));
    private CodingTargetSelectionService service;

    @BeforeEach
    void setUp() {
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        service = new CodingTargetSelectionService(
                accessPolicy, bindings, preflight, profiles, transactions);
        when(accessPolicy.requireVisibleWorkItem(
                        access, organizationId, teamId, projectId, workItem.id()))
                .thenReturn(workItem);
    }

    @Test
    void listsOnlyDeploymentApprovedProfilesAfterWorkItemAuthorization() {
        assertEquals(List.of(profile), service.listBuildProfiles(
                access, organizationId, teamId, projectId, workItem.id()));

        verify(accessPolicy).requireVisibleWorkItem(
                access, organizationId, teamId, projectId, workItem.id());
    }

    @Test
    void preflightsAMemberSelectedRefWithoutExposingAHostPath() {
        RepositoryBindingPreflightResult expected = new RepositoryBindingPreflightResult(
                binding.repositoryKey(),
                new RepositoryBranchName("feature/coding"),
                new RepositoryCommitId("d".repeat(40)));
        when(bindings.findById(organizationId, teamId, projectId, binding.id()))
                .thenReturn(Optional.of(binding));
        when(preflight.preflight(binding, expected.baselineRef())).thenReturn(expected);

        assertEquals(expected, service.preflight(
                access,
                organizationId,
                teamId,
                projectId,
                workItem.id(),
                binding.id(),
                expected.baselineRef()));
    }

    @Test
    void rejectsCrossScopeBindingBeforeCallingRepositoryPreflight() {
        when(bindings.findById(organizationId, teamId, projectId, binding.id()))
                .thenReturn(Optional.empty());

        assertThrows(DomainValidationException.class, () -> service.preflight(
                access,
                organizationId,
                teamId,
                projectId,
                workItem.id(),
                binding.id(),
                new RepositoryBranchName("main")));

        verifyNoInteractions(preflight);
    }

    private static BuildProfile profile() {
        return BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN,
                17,
                new SandboxImageReference("maven@sha256:" + "e".repeat(64)),
                CommandCatalog.of(
                        CommandKind.TEST,
                        new BuildCommand(
                                "coding.maven.test",
                                List.of("mvn", "test"),
                                ".",
                                60,
                                900)));
    }
}

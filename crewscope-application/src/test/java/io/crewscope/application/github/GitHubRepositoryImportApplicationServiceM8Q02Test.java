package io.crewscope.application.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** API-side regression: create records durable work and has no Worker I/O dependency. */
class GitHubRepositoryImportApplicationServiceM8Q02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-02T08:00:00Z");

    @Test
    void createPersistsRequestedJobWithoutExecutingRepositoryIo() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Repository administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamCommandContext context = new TeamCommandContext(
                new TeamAccessContext(actor, true),
                IdempotencyKey.from("github-import-request"),
                UUID.randomUUID(),
                Optional.empty());
        ConnectionId connectionId = ConnectionId.generate();
        ConnectionGrantId grantId = ConnectionGrantId.generate();
        CreateGitHubRepositoryImportCommand command = new CreateGitHubRepositoryImportCommand(
                connectionId,
                3,
                grantId,
                7,
                "4815162342",
                new RepositoryKey("crewscope"),
                new RepositoryBranchName("main"));
        GitHubRepositoryImportJobRepository jobs = mock(
                GitHubRepositoryImportJobRepository.class);
        when(jobs.findActiveByTarget(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(jobs.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GitHubRepositoryImportAuthorizationService authorization = mock(
                GitHubRepositoryImportAuthorizationService.class);
        GitHubRepositoryImportAuthorization authorized = mock(
                GitHubRepositoryImportAuthorization.class);
        GitHubRepositoryCatalogEntry catalog = mock(GitHubRepositoryCatalogEntry.class);
        when(catalog.externalRepositoryId()).thenReturn("4815162342");
        when(catalog.fullName()).thenReturn("crewscope/crewscope");
        when(authorized.catalog()).thenReturn(catalog);
        when(authorization.authorize(
                        any(), any(), any(), any(), any(), any(), anyLong(),
                        any(), anyLong(), any()))
                .thenReturn(authorized);
        RepositoryBindingAccessPolicy accessPolicy = mock(RepositoryBindingAccessPolicy.class);
        GitHubRepositoryImportApplicationService service =
                new GitHubRepositoryImportApplicationService(
                        jobs, authorization, accessPolicy, () -> NOW);

        GitHubRepositoryImportJob result = service.create(
                context, organizationId, teamId, projectId, command);

        assertEquals(GitHubRepositoryImportStatus.REQUESTED, result.status());
        assertEquals(0, result.attempt());
        assertEquals(actor.id(), result.createdBy());
        assertTrue(result.createdByPlatformAdministrator());
        verify(jobs).create(result);
    }

    @Test
    void importingJobCannotBeCancelledAfterRepositoryIoStarts() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Repository administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamCommandContext context = new TeamCommandContext(
                new TeamAccessContext(actor, true),
                IdempotencyKey.from("github-import-cancel"),
                UUID.randomUUID(),
                Optional.empty());
        GitHubRepositoryImportJob importing = GitHubRepositoryImportJob.requested(
                        organizationId,
                        teamId,
                        projectId,
                        ConnectionId.generate(),
                        1,
                        ConnectionGrantId.generate(),
                        1,
                        "4815162342",
                        "crewscope/crewscope",
                        new RepositoryKey("crewscope"),
                        new RepositoryBranchName("main"),
                        actor.id(),
                        true,
                        NOW)
                .progress(
                        GitHubRepositoryImportStatus.IMPORTING,
                        35,
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        NOW);
        GitHubRepositoryImportJobRepository jobs = mock(
                GitHubRepositoryImportJobRepository.class);
        when(jobs.findById(organizationId, teamId, projectId, importing.id()))
                .thenReturn(Optional.of(importing));
        RepositoryBindingAccessPolicy accessPolicy = mock(RepositoryBindingAccessPolicy.class);
        GitHubRepositoryImportApplicationService service =
                new GitHubRepositoryImportApplicationService(
                        jobs,
                        mock(GitHubRepositoryImportAuthorizationService.class),
                        accessPolicy,
                        () -> NOW);

        GitHubProviderException failure = assertThrows(
                GitHubProviderException.class,
                () -> service.cancel(
                        context, organizationId, teamId, projectId, importing.id()));

        assertEquals(GitHubProviderErrorCode.CONFLICT, failure.code());
        verify(jobs, never()).cancelBeforeImport(any(), any());
    }

    @Test
    void cancellationLosesRaceToImportAndFailsClosed() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        Principal actor = actor(organizationId);
        TeamCommandContext context = commandContext(actor, "github-import-cancel-race");
        GitHubRepositoryImportJob preflighting = requested(
                        organizationId, teamId, projectId, actor, "crewscope")
                .progress(
                        GitHubRepositoryImportStatus.PREFLIGHTING,
                        10,
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        NOW);
        GitHubRepositoryImportJob importing = preflighting.progress(
                GitHubRepositoryImportStatus.IMPORTING,
                35,
                Optional.empty(),
                Optional.empty(),
                1,
                NOW);
        GitHubRepositoryImportJobRepository jobs = mock(
                GitHubRepositoryImportJobRepository.class);
        when(jobs.findById(organizationId, teamId, projectId, preflighting.id()))
                .thenReturn(Optional.of(preflighting), Optional.of(importing));
        when(jobs.cancelBeforeImport(preflighting, NOW)).thenReturn(Optional.empty());
        GitHubRepositoryImportApplicationService service = service(jobs);

        GitHubProviderException failure = assertThrows(
                GitHubProviderException.class,
                () -> service.cancel(
                        context, organizationId, teamId, projectId, preflighting.id()));

        assertEquals(GitHubProviderErrorCode.CONFLICT, failure.code());
        verify(jobs).cancelBeforeImport(preflighting, NOW);
    }

    @Test
    void repositoryKeyOwnedByAnotherTargetIsRejectedAcrossProjects() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkProjectId firstProject = WorkProjectId.generate();
        WorkProjectId secondProject = WorkProjectId.generate();
        Principal actor = actor(organizationId);
        TeamCommandContext context = commandContext(actor, "github-import-key-conflict");
        GitHubRepositoryImportJob keyOwner = requested(
                organizationId, teamId, firstProject, actor, "shared-key");
        GitHubRepositoryImportJobRepository jobs = mock(
                GitHubRepositoryImportJobRepository.class);
        when(jobs.findActiveByTarget(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(jobs.findByRepositoryKey(new RepositoryKey("shared-key")))
                .thenReturn(Optional.of(keyOwner));
        GitHubRepositoryImportAuthorizationService authorization = authorizedRepository();
        GitHubRepositoryImportApplicationService service = new GitHubRepositoryImportApplicationService(
                jobs,
                authorization,
                mock(RepositoryBindingAccessPolicy.class),
                () -> NOW);
        CreateGitHubRepositoryImportCommand command = new CreateGitHubRepositoryImportCommand(
                ConnectionId.generate(),
                1,
                ConnectionGrantId.generate(),
                1,
                "4815162342",
                new RepositoryKey("shared-key"),
                new RepositoryBranchName("main"));

        GitHubProviderException failure = assertThrows(
                GitHubProviderException.class,
                () -> service.create(
                        context, organizationId, teamId, secondProject, command));

        assertEquals(GitHubProviderErrorCode.CONFLICT, failure.code());
        verify(jobs, never()).create(any());
    }

    @Test
    void terminalImportFieldsRemainStateConsistent() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = actor(organizationId);
        GitHubRepositoryImportJob job = requested(
                organizationId,
                TeamId.generate(),
                WorkProjectId.generate(),
                actor,
                "crewscope");

        assertThrows(
                IllegalArgumentException.class,
                () -> job.progress(
                        GitHubRepositoryImportStatus.READY,
                        100,
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> job.progress(
                        GitHubRepositoryImportStatus.FAILED,
                        100,
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        NOW));
    }

    private static GitHubRepositoryImportApplicationService service(
            GitHubRepositoryImportJobRepository jobs) {
        return new GitHubRepositoryImportApplicationService(
                jobs,
                mock(GitHubRepositoryImportAuthorizationService.class),
                mock(RepositoryBindingAccessPolicy.class),
                () -> NOW);
    }

    private static GitHubRepositoryImportAuthorizationService authorizedRepository() {
        GitHubRepositoryImportAuthorizationService authorization = mock(
                GitHubRepositoryImportAuthorizationService.class);
        GitHubRepositoryImportAuthorization authorized = mock(
                GitHubRepositoryImportAuthorization.class);
        GitHubRepositoryCatalogEntry catalog = mock(GitHubRepositoryCatalogEntry.class);
        when(catalog.externalRepositoryId()).thenReturn("4815162342");
        when(catalog.fullName()).thenReturn("crewscope/crewscope");
        when(authorized.catalog()).thenReturn(catalog);
        when(authorization.authorize(
                        any(), any(), any(), any(), any(), any(), anyLong(),
                        any(), anyLong(), any()))
                .thenReturn(authorized);
        return authorization;
    }

    private static TeamCommandContext commandContext(Principal actor, String idempotencyKey) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, true),
                IdempotencyKey.from(idempotencyKey),
                UUID.randomUUID(),
                Optional.empty());
    }

    private static Principal actor(OrganizationId organizationId) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Repository administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static GitHubRepositoryImportJob requested(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            Principal actor,
            String repositoryKey) {
        return GitHubRepositoryImportJob.requested(
                organizationId,
                teamId,
                projectId,
                ConnectionId.generate(),
                1,
                ConnectionGrantId.generate(),
                1,
                "4815162342",
                "crewscope/crewscope",
                new RepositoryKey(repositoryKey),
                new RepositoryBranchName("main"),
                actor.id(),
                true,
                NOW);
    }
}

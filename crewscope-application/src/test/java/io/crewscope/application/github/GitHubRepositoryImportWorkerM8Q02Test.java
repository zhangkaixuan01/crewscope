package io.crewscope.application.github;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Worker-side regression for authority revalidation, import and exactly-one Binding creation. */
class GitHubRepositoryImportWorkerM8Q02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-02T08:00:00Z");

    @Test
    void claimedJobRevalidatesAuthorityAndCompletesWithOneBinding() {
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
        GitHubRepositoryImportJob requested = GitHubRepositoryImportJob.requested(
                organizationId,
                teamId,
                projectId,
                ConnectionId.generate(),
                1,
                ConnectionGrantId.generate(),
                2,
                "4815162342",
                "crewscope/crewscope",
                new RepositoryKey("crewscope"),
                new RepositoryBranchName("main"),
                actor.id(),
                true,
                NOW);
        GitHubRepositoryImportJob claimed = requested.progress(
                GitHubRepositoryImportStatus.PREFLIGHTING,
                10,
                Optional.empty(),
                Optional.empty(),
                1,
                NOW);
        GitHubRepositoryImportJobRepository jobs = mock(
                GitHubRepositoryImportJobRepository.class);
        when(jobs.claimNext(eq("worker-1"), any(), any())).thenReturn(Optional.of(
                new GitHubRepositoryImportLease(
                        claimed, "worker-1", UtcTimestamp.parse("2026-09-02T08:30:00Z"))));
        when(jobs.updateClaimed(any(), eq("worker-1"), any(), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        GitHubRepositoryImportAuthorizationService authorization = mock(
                GitHubRepositoryImportAuthorizationService.class);
        GitHubRepositoryImportAuthorization authorized = mock(
                GitHubRepositoryImportAuthorization.class);
        GitHubRepositoryCatalogEntry catalog = mock(GitHubRepositoryCatalogEntry.class);
        GitHubAccessRequest access = mock(GitHubAccessRequest.class);
        GitHubRepositoryPolicy policy = mock(GitHubRepositoryPolicy.class);
        when(catalog.fullName()).thenReturn("crewscope/crewscope");
        when(authorized.catalog()).thenReturn(catalog);
        when(authorized.access()).thenReturn(access);
        when(authorized.policy()).thenReturn(policy);
        when(authorization.authorize(
                        any(), any(), any(), any(), any(), any(), anyLong(),
                        any(), anyLong(), any()))
                .thenReturn(authorized);
        GitHubProviderPort provider = mock(GitHubProviderPort.class);
        GitHubRepositoryImportPort importer = mock(GitHubRepositoryImportPort.class);
        RepositoryBindingApplicationService bindingService = mock(
                RepositoryBindingApplicationService.class);
        RepositoryBindingRepository bindingRepository = mock(RepositoryBindingRepository.class);
        when(bindingRepository.findByKey(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        RepositoryBinding binding = mock(RepositoryBinding.class);
        when(binding.id()).thenReturn(RepositoryBindingId.generate());
        when(bindingService.create(any(), any(), any(), any())).thenReturn(
                CommandExecution.completed(
                        binding,
                        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID())));
        PrincipalRepository principals = mock(PrincipalRepository.class);
        when(principals.findById(organizationId, actor.id())).thenReturn(Optional.of(actor));
        GitHubRepositoryImportWorker worker = new GitHubRepositoryImportWorker(
                jobs,
                authorization,
                provider,
                importer,
                bindingService,
                bindingRepository,
                principals,
                () -> NOW,
                "worker-1",
                Duration.ofMinutes(30));

        assertTrue(worker.runOnce());

        verify(authorization).authorize(
                any(), eq(claimed.id()), eq(organizationId), eq(teamId), eq(projectId),
                eq(claimed.connectionId()), eq(1L), eq(claimed.grantId()), eq(2L),
                eq("4815162342"));
        verify(provider).preflightRepository(any());
        verify(importer).importRepository(any());
        verify(bindingService, times(1)).create(any(), eq(teamId), eq(projectId), any());
        ArgumentCaptor<GitHubRepositoryImportJob> progress =
                ArgumentCaptor.forClass(GitHubRepositoryImportJob.class);
        verify(jobs, times(2)).updateClaimed(
                progress.capture(), eq("worker-1"), any(), eq(Duration.ofMinutes(30)));
        assertTrue(progress.getAllValues().stream()
                .anyMatch(value -> value.status() == GitHubRepositoryImportStatus.READY
                        && value.bindingId().orElseThrow().equals(binding.id())));
    }
}

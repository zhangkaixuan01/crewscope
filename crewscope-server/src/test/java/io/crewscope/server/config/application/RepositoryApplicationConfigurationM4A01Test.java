package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves the pure Server profile fails Repository Preflight closed without Worker adapters. */
class RepositoryApplicationConfigurationM4A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-19T14:00:00Z");

    @Test
    void wiresAStableUnavailablePreflightFallbackWhenNoWorkerPortExists() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Platform administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamInitialization team = TeamInitialization.create(actor, "Team", NOW);
        WorkProject project = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("CODE"),
                "Code",
                team.team(),
                team.defaultWorkspace(),
                actor,
                NOW);
        WorkProjectRepository projects = mock(WorkProjectRepository.class);
        when(projects.findById(organizationId, project.id())).thenReturn(Optional.of(project));

        new ApplicationContextRunner()
                .withUserConfiguration(RepositoryApplicationConfiguration.class)
                .withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
                .withBean(WorkProjectRepository.class, () -> projects)
                .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
                .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
                .withBean(
                        RepositoryBindingRepository.class,
                        () -> mock(RepositoryBindingRepository.class))
                .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
                .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> () -> NOW)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RepositoryBindingAccessPolicy.class);
                    assertThat(context).hasSingleBean(RepositoryBindingApplicationService.class);

                    RepositoryBindingApplicationService service =
                            context.getBean(RepositoryBindingApplicationService.class);
                    assertThatThrownBy(() -> service.preflightDraft(
                                    new TeamAccessContext(actor, true),
                                    organizationId,
                                    team.team().id(),
                                    project.id(),
                                    new RepositoryKey("crewscope"),
                                    new RepositoryBranchName("main")))
                            .isInstanceOf(RepositoryBindingPreflightException.class)
                            .satisfies(failure -> assertThat(
                                            ((RepositoryBindingPreflightException) failure).error())
                                    .isEqualTo(RepositoryBindingPreflightError.SERVICE_UNAVAILABLE))
                            .hasMessage("Repository Preflight is unavailable on this server");
                });
    }
}

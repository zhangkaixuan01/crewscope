package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingPreflightError;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit Spring wiring for RepositoryBinding management APIs. */
@Configuration(proxyBeanMethods = false)
public class RepositoryApplicationConfiguration {

    @Bean
    RepositoryBindingAccessPolicy repositoryBindingAccessPolicy(
            WorkItemAccessPolicy workItemAccessPolicy,
            WorkProjectRepository workProjectRepository,
            TeamRoleRepository teamRoleRepository,
            MemberRoleRepository memberRoleRepository) {
        return new RepositoryBindingAccessPolicy(
                workItemAccessPolicy,
                workProjectRepository,
                teamRoleRepository,
                memberRoleRepository);
    }

    @Bean
    RepositoryBindingApplicationService repositoryBindingApplicationService(
            RepositoryBindingRepository bindingRepository,
            RepositoryBindingAccessPolicy accessPolicy,
            ObjectProvider<RepositoryBindingPreflightPort> preflightPorts,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        RepositoryBindingPreflightPort preflightPort = preflightPorts.getIfAvailable(() ->
                (binding, baselineRef) -> {
                    throw new RepositoryBindingPreflightException(
                            RepositoryBindingPreflightError.SERVICE_UNAVAILABLE,
                            "Repository Preflight is unavailable on this server");
                });
        return new RepositoryBindingApplicationService(
                bindingRepository,
                accessPolicy,
                preflightPort,
                domainEventStore,
                outboxRepository,
                receiptStore,
                transactionExecutor,
                timeProvider);
    }
}

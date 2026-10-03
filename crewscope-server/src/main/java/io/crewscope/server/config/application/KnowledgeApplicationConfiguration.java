package io.crewscope.server.config.application;

import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntime;
import io.crewscope.agentscope.knowledge.KnowledgeDistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.knowledge.KnowledgeCommandService;
import io.crewscope.application.knowledge.KnowledgeDistillationPort;
import io.crewscope.application.knowledge.KnowledgeDistillationService;
import io.crewscope.application.knowledge.KnowledgeDistillationSourceAssembler;
import io.crewscope.application.knowledge.KnowledgeDistillerProvisioningService;
import io.crewscope.application.knowledge.KnowledgeDistillerRepository;
import io.crewscope.application.knowledge.KnowledgeDistillerReadiness;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition root for the M10-A02 knowledge management and distillation face. */
@Configuration(proxyBeanMethods = false)
public class KnowledgeApplicationConfiguration {

    @Bean
    KnowledgeCommandService knowledgeCommandService(
            KnowledgeRepository knowledgeRepository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new KnowledgeCommandService(
                knowledgeRepository,
                teams,
                memberships,
                roles,
                grants,
                events,
                outbox,
                receipts,
                transactions,
                timeProvider);
    }

    @Bean
    KnowledgeDistillationSourceAssembler knowledgeDistillationSourceAssembler(
            TaskEventRepository taskEvents) {
        return new KnowledgeDistillationSourceAssembler(taskEvents);
    }

    @Bean
    KnowledgeDistillerTemplateRuntimeRegistry knowledgeDistillerTemplateRuntimeRegistry() {
        return new KnowledgeDistillerTemplateRuntimeRegistry();
    }

    @Bean
    KnowledgeDistillerProvisioningService knowledgeDistillerProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            KnowledgeDistillerRepository distillers,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new KnowledgeDistillerProvisioningService(
                teams,
                workspaces,
                members,
                principals,
                templateCatalog,
                templates,
                distillers,
                configurations,
                configurationService,
                transactions,
                timeProvider);
    }

    /**
     * Keeps the stage-3/stage-4 blocking transactions of a distillation off the Provider
     * client's completion threads (same shape as the registration persistence executor).
     */
    @Bean(name = "knowledgeDistillationPersistenceExecutor", destroyMethod = "shutdown")
    ExecutorService knowledgeDistillationPersistenceExecutor() {
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                task -> {
                    Thread thread = new Thread(
                            task, "crewscope-distillation-db-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * The readiness port carries no result; the concrete provisioning service keeps its
     * rich return type for direct tests.
     */
    @Bean
    KnowledgeDistillerReadiness knowledgeDistillerReadiness(
            KnowledgeDistillerProvisioningService provisioning) {
        return (organizationId, teamId) -> provisioning.ensureReady(organizationId, teamId);
    }

    @Bean
    KnowledgeDistillerRuntime knowledgeDistillerRuntime(
            AgentTemplateRuntimeRegistry agents,
            KnowledgeDistillerTemplateRuntimeRegistry templates) {
        return new KnowledgeDistillerRuntime(agents, templates, Duration.ofMinutes(2));
    }

    @Bean
    KnowledgeDistillationPort knowledgeDistillationPort(
            TeamRepository teams,
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService resolver,
            AgentTemplateRuntimeAssembler assembler,
            KnowledgeDistillerTemplateRuntimeRegistry registry,
            KnowledgeDistillerRuntime runtime,
            TimeProvider timeProvider) {
        return new AgentScopeKnowledgeDistillationAdapter(
                teams,
                profiles,
                principals,
                templates,
                configurations,
                connections,
                governance,
                resolver,
                assembler,
                registry,
                runtime,
                timeProvider);
    }

    @Bean
    KnowledgeDistillationService knowledgeDistillationService(
            KnowledgeRepository knowledgeRepository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            KnowledgeDistillationSourceAssembler sourceAssembler,
            KnowledgeDistillerReadiness distillerReadiness,
            KnowledgeDistillationPort port,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            @Qualifier("knowledgeDistillationPersistenceExecutor") Executor persistenceExecutor) {
        return new KnowledgeDistillationService(
                knowledgeRepository,
                teams,
                memberships,
                roles,
                grants,
                tasks,
                executions,
                sourceAssembler,
                distillerReadiness,
                port,
                events,
                outbox,
                receipts,
                transactions,
                timeProvider,
                persistenceExecutor);
    }
}

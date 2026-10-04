package io.crewscope.server.config.application;

import io.crewscope.agentscope.coding.CodingSpecialistSkillBundle;
import io.crewscope.agentscope.skill.SkillDistillerRuntime;
import io.crewscope.agentscope.skill.SkillDistillerTemplateRuntimeRegistry;
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
import io.crewscope.application.knowledge.KnowledgeDistillationSourceAssembler;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.application.skill.DefaultTeamSkillExecutionSource;
import io.crewscope.application.skill.SkillDistillationPort;
import io.crewscope.application.skill.SkillDistillationService;
import io.crewscope.application.skill.SkillDistillerProvisioningService;
import io.crewscope.application.skill.SkillDistillerRepository;
import io.crewscope.application.skill.TeamSkillCatalogCeilingContributor;
import io.crewscope.application.skill.TeamSkillCommandService;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import io.crewscope.application.skill.TeamSkillRepository;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit composition root for the M10 Team Skill face. The catalog commands and the
 * A03b distillation chain are always assembled — the {@code crewscope.skill.enabled}
 * switch only feeds their write gates, so reads keep working on deployments that have
 * not opted in (the same shape as the agent-memory configuration). The execution source
 * additionally consults {@code crewscope.injection.enabled}: with injection off no
 * dynamic Team Skill loads, while the built-in Coding Skill keeps its M9 behavior.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SkillProperties.class, InjectionBudgetProperties.class})
public class TeamSkillConfiguration {

    @Bean
    TeamSkillCommandService teamSkillCommandService(
            TeamSkillRepository skillRepository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            SkillProperties properties) {
        return new TeamSkillCommandService(
                skillRepository,
                teams,
                memberships,
                roles,
                grants,
                events,
                outbox,
                receipts,
                transactions,
                timeProvider,
                properties.isEnabled());
    }

    /** The save-side ceiling proof: PUBLISHED heads of the Team's live catalog (D1). */
    @Bean
    TeamSkillCatalogCeilingContributor teamSkillCatalogCeilingContributor(
            TeamSkillRepository skillRepository) {
        return new TeamSkillCatalogCeilingContributor(skillRepository);
    }

    @Bean
    SkillDistillerTemplateRuntimeRegistry skillDistillerTemplateRuntimeRegistry() {
        return new SkillDistillerTemplateRuntimeRegistry();
    }

    @Bean
    SkillDistillerProvisioningService skillDistillerProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            SkillDistillerRepository distillers,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new SkillDistillerProvisioningService(
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
     * Keeps the stage-3/stage-4 blocking transactions of a skill distillation off the
     * Provider client's completion threads (same shape as the knowledge executor).
     */
    @Bean(name = "skillDistillationPersistenceExecutor", destroyMethod = "shutdown")
    ExecutorService skillDistillationPersistenceExecutor() {
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
                            task, "crewscope-skill-distillation-db-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * The provisioning service is itself the readiness port (its ensureReady is
     * idempotent); the application service consumes it lazily on the first
     * distillation, never at startup.
     */

    @Bean
    SkillDistillerRuntime skillDistillerRuntime(
            AgentTemplateRuntimeRegistry agents,
            SkillDistillerTemplateRuntimeRegistry templates) {
        return new SkillDistillerRuntime(agents, templates, Duration.ofMinutes(10));
    }

    @Bean
    SkillDistillationPort skillDistillationPort(
            TeamRepository teams,
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService resolver,
            AgentTemplateRuntimeAssembler assembler,
            SkillDistillerTemplateRuntimeRegistry registry,
            SkillDistillerRuntime runtime,
            TimeProvider timeProvider) {
        return new AgentScopeSkillDistillationAdapter(
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
    SkillDistillationService skillDistillationService(
            TeamSkillRepository skillRepository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            KnowledgeDistillationSourceAssembler sourceAssembler,
            SkillDistillerProvisioningService distillerReadiness,
            SkillDistillationPort port,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            @Qualifier("skillDistillationPersistenceExecutor") Executor persistenceExecutor,
            SkillProperties properties) {
        return new SkillDistillationService(
                skillRepository,
                teams,
                memberships,
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
                persistenceExecutor,
                properties.isEnabled());
    }

    /**
     * The pinned-execution resolver both worker faces consume (load evidence and
     * injection manifest stay the same source, D2). The built-in Coding Skill's source
     * id is excluded so it never appears as a dynamic reference.
     */
    @Bean
    TeamSkillExecutionSource teamSkillExecutionSource(
            TeamSkillRepository skillRepository,
            InjectionManifestRepository manifests,
            AgentConfigurationRepository configurations,
            SkillProperties skillProperties,
            InjectionBudgetProperties injectionProperties) {
        return new DefaultTeamSkillExecutionSource(
                skillRepository,
                manifests,
                configurations,
                CodingSpecialistSkillBundle.SKILL_ID,
                skillProperties.isEnabled(),
                injectionProperties.isEnabled());
    }
}

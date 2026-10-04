package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
import io.crewscope.application.skill.SkillDistillationPort;
import io.crewscope.application.skill.SkillDistillationService;
import io.crewscope.application.skill.SkillDistillerProvisioningService;
import io.crewscope.application.skill.SkillDistillerReadiness;
import io.crewscope.application.skill.SkillDistillerRepository;
import io.crewscope.application.skill.TeamSkillCatalogCeilingContributor;
import io.crewscope.application.skill.TeamSkillCommandService;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import io.crewscope.application.skill.TeamSkillRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * M10 assembly contract: the catalog commands and the A03b distillation chain are
 * always assembled — the {@code crewscope.skill.enabled} switch only feeds their write
 * gates, so reads keep answering on deployments that have not opted in. The execution
 * source additionally consults {@code crewscope.injection.enabled}: with injection off
 * no dynamic Team Skill loads while the built-in Coding Skill keeps its M9 behavior.
 */
class TeamSkillConfigurationTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T11:00:00Z");

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(TeamSkillConfiguration.class)
      .withBean(TeamSkillRepository.class, () -> mock(TeamSkillRepository.class))
      .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
      .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
      .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
      .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
      .withBean(TeamMemberRepository.class, () -> mock(TeamMemberRepository.class))
      .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
      .withBean(AgentProfileRepository.class, () -> mock(AgentProfileRepository.class))
      .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
      .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
      .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
      .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
      .withBean(TransactionExecutor.class, TeamSkillConfigurationTest::directTransactions)
      .withBean(TimeProvider.class, () -> () -> NOW)
      .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
      .withBean(TaskExecutionRepository.class, () -> mock(TaskExecutionRepository.class))
      .withBean(TaskEventRepository.class, () -> mock(TaskEventRepository.class))
      .withBean(AgentTemplateCatalogInitializer.class,
          () -> mock(AgentTemplateCatalogInitializer.class))
      .withBean(AgentTemplateRepository.class, () -> mock(AgentTemplateRepository.class))
      .withBean(AgentConfigurationRepository.class,
          () -> mock(AgentConfigurationRepository.class))
      .withBean(AgentConfigurationApplicationService.class,
          () -> mock(AgentConfigurationApplicationService.class))
      .withBean(SkillDistillerRepository.class, () -> mock(SkillDistillerRepository.class))
      .withBean(KnowledgeDistillationSourceAssembler.class,
          () -> new KnowledgeDistillationSourceAssembler(mock(TaskEventRepository.class)))
      .withBean(AgentTemplateRuntimeRegistry.class,
          () -> mock(AgentTemplateRuntimeRegistry.class))
      .withBean(AgentTemplateRuntimeAssembler.class,
          () -> mock(AgentTemplateRuntimeAssembler.class))
      .withBean(AgentExecutionConfigurationService.class,
          () -> mock(AgentExecutionConfigurationService.class))
      .withBean(AgentModelGovernance.class, () -> mock(AgentModelGovernance.class))
      .withBean(ModelConnectionRepository.class,
          () -> mock(ModelConnectionRepository.class))
      .withBean(InjectionManifestRepository.class,
          () -> mock(InjectionManifestRepository.class));

  @Test
  void theDefaultDeploymentAssemblesTheServiceWithTheSwitchOff() {
    runner.run(context -> {
      context.assertThat()
          .hasNotFailed()
          .hasSingleBean(TeamSkillCommandService.class)
          .hasSingleBean(SkillProperties.class);
      assertFalse(context.getBean(SkillProperties.class).isEnabled());
    });
  }

  @Test
  void theSwitchFlipsTheWriteGateWithoutChangingTheAssembly() {
    runner.withPropertyValues("crewscope.skill.enabled=true")
        .run(context -> {
          context.assertThat()
              .hasNotFailed()
              .hasSingleBean(TeamSkillCommandService.class);
          assertTrue(context.getBean(SkillProperties.class).isEnabled());
        });
  }

  /** A03b: the distillation chain and both worker-facing sources always assemble. */
  @Test
  void theDistillationChainAndExecutionSourceAssembleOnEveryDeployment() {
    runner.run(context -> {
      context.assertThat()
          .hasNotFailed()
          .hasSingleBean(SkillDistillerProvisioningService.class)
          .hasSingleBean(SkillDistillerReadiness.class)
          .hasSingleBean(SkillDistillationPort.class)
          .hasSingleBean(SkillDistillationService.class)
          .hasSingleBean(TeamSkillExecutionSource.class)
          .hasSingleBean(TeamSkillCatalogCeilingContributor.class);
    });
  }

  /**
   * A03b D3: injection off means no dynamic loads even with the skill switch on — the
   * resolver must answer empty for a pinned configuration under the default switches.
   */
  @Test
  void injectionOffKeepsTheDynamicResolutionEmptyWhileSkillsAreEnabled() {
    runner.withPropertyValues("crewscope.skill.enabled=true").run(context -> {
      context.assertThat().hasNotFailed().hasSingleBean(TeamSkillExecutionSource.class);
      assertTrue(context.getBean(SkillProperties.class).isEnabled());
      assertFalse(context.getBean(InjectionBudgetProperties.class).isEnabled());
      TeamSkillExecutionSource source = context.getBean(TeamSkillExecutionSource.class);
      assertTrue(source.resolveForPinnedExecution(
              io.crewscope.domain.shared.id.OrganizationId.generate(),
              io.crewscope.domain.shared.id.TeamId.generate(),
              java.util.Optional.empty(),
              io.crewscope.domain.task.TaskExecutionId.generate(),
              1).isEmpty());
    });
  }

  private static TransactionExecutor directTransactions() {
    return new TransactionExecutor() {
      @Override
      public <T> T required(java.util.function.Supplier<T> operation) {
        return operation.get();
      }
    };
  }
}

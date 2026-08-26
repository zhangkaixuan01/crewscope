package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.agentscope.review.ReviewerSpecialistRuntime;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.coding.CodingArtifactContentPort;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.review.ContextPackageBuilder;
import io.crewscope.application.review.ContextPackageRepository;
import io.crewscope.application.review.ReviewDecisionRepository;
import io.crewscope.application.review.ReviewEventPublisher;
import io.crewscope.application.review.ReviewFindingBatchRecorder;
import io.crewscope.application.review.ReviewFindingObservationRepository;
import io.crewscope.application.review.ReviewFindingRepository;
import io.crewscope.application.review.ReviewGateApplicationService;
import io.crewscope.application.review.ReviewModificationRoundRepository;
import io.crewscope.application.review.ReviewQueryRepository;
import io.crewscope.application.review.ReviewRequestApplicationService;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.application.review.ReviewSubjectRepository;
import io.crewscope.application.review.ReviewerExecutionApplicationService;
import io.crewscope.application.review.ReviewerExecutionPort;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves M5-A05 stays on explicit constructor composition without component scanning. */
class ReviewApplicationCompositionTest {

    @Test
    void wiresReviewRequestReviewerAndGateServicesExactlyOnce() {
        new ApplicationContextRunner()
                .withUserConfiguration(ReviewApplicationConfiguration.class)
                .withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
                .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
                .withBean(TaskExecutionRepository.class, () -> mock(TaskExecutionRepository.class))
                .withBean(DiffArtifactRepository.class, () -> mock(DiffArtifactRepository.class))
                .withBean(TestEvidenceRepository.class, () -> mock(TestEvidenceRepository.class))
                .withBean(CommandEvidenceRepository.class, () -> mock(CommandEvidenceRepository.class))
                .withBean(PolicySnapshotRepository.class, () -> mock(PolicySnapshotRepository.class))
                .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
                .withBean(AgentProfileRepository.class, () -> mock(AgentProfileRepository.class))
                .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
                .withBean(ResponsibilityAssignmentRepository.class,
                        () -> mock(ResponsibilityAssignmentRepository.class))
                .withBean(ReviewSubjectRepository.class, () -> mock(ReviewSubjectRepository.class))
                .withBean(ContextPackageRepository.class, () -> mock(ContextPackageRepository.class))
                .withBean(ReviewRequestRepository.class, () -> mock(ReviewRequestRepository.class))
                .withBean(ReviewFindingRepository.class, () -> mock(ReviewFindingRepository.class))
                .withBean(ReviewFindingObservationRepository.class,
                        () -> mock(ReviewFindingObservationRepository.class))
                .withBean(ReviewDecisionRepository.class, () -> mock(ReviewDecisionRepository.class))
                .withBean(ReviewModificationRoundRepository.class,
                        () -> mock(ReviewModificationRoundRepository.class))
                .withBean(ReviewQueryRepository.class, () -> mock(ReviewQueryRepository.class))
                .withBean(TaskAgentRuntimeSessionRepository.class,
                        () -> mock(TaskAgentRuntimeSessionRepository.class))
                .withBean(CodingArtifactContentPort.class, () -> mock(CodingArtifactContentPort.class))
                .withBean(GateReviewerPolicyProvider.class,
                        () -> mock(GateReviewerPolicyProvider.class))
                .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
                // The persistence module owns the durable publisher; this isolated application
                // composition test supplies the port exactly as production scanning does.
                .withBean(ReviewEventPublisher.class, () -> mock(ReviewEventPublisher.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .withBean(AgentTemplateRepository.class, () -> mock(AgentTemplateRepository.class))
                .withBean(AgentConfigurationRepository.class,
                        () -> mock(AgentConfigurationRepository.class))
                .withBean(AgentTemplateRuntimeAssembler.class,
                        () -> mock(AgentTemplateRuntimeAssembler.class))
                .withBean(AgentTemplateRuntimeRegistry.class,
                        () -> mock(AgentTemplateRuntimeRegistry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ContextPackageBuilder.class);
                    assertThat(context).hasSingleBean(ReviewFindingBatchRecorder.class);
                    assertThat(context).hasSingleBean(ReviewEventPublisher.class);
                    assertThat(context).hasSingleBean(ReviewerSpecialistRuntime.class);
                    assertThat(context).hasSingleBean(ReviewerExecutionPort.class);
                    assertThat(context).hasSingleBean(ReviewRequestApplicationService.class);
                    assertThat(context).hasSingleBean(ReviewerExecutionApplicationService.class);
                    assertThat(context).hasSingleBean(ReviewGateApplicationService.class);
                });
    }
}

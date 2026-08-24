package io.crewscope.server.config.application;

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
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.review.ContextPackageBuilder;
import io.crewscope.application.review.ContextPackageRepository;
import io.crewscope.application.review.DurableReviewEventPublisher;
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
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit constructor composition for the M5 Review and human Gate application boundary. */
@Configuration(proxyBeanMethods = false)
public class ReviewApplicationConfiguration {

    @Bean
    ContextPackageBuilder contextPackageBuilder(CodingArtifactContentPort artifacts) {
        return new ContextPackageBuilder(artifacts);
    }

    @Bean
    ReviewFindingBatchRecorder reviewFindingBatchRecorder(
            ReviewFindingRepository findings,
            ReviewFindingObservationRepository observations) {
        return new ReviewFindingBatchRecorder(findings, observations);
    }

    @Bean
    ReviewEventPublisher reviewEventPublisher(
            DomainEventStore events,
            TaskEventRepository taskEvents,
            OutboxRepository outbox,
            TransactionExecutor transactions) {
        return new DurableReviewEventPublisher(events, taskEvents, outbox, transactions);
    }

    @Bean
    ReviewerSpecialistRuntime reviewerSpecialistRuntime(
            AgentTemplateRuntimeRegistry agents,
            ReviewFindingBatchRecorder recorder) {
        return new ReviewerSpecialistRuntime(agents, recorder, Duration.ofMinutes(5));
    }

    @Bean
    ReviewerExecutionPort reviewerExecutionPort(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentTemplateRuntimeAssembler assembler,
            ReviewerSpecialistRuntime runtime) {
        return new AgentScopeReviewerExecutionAdapter(
                profiles, templates, configurations, assembler, runtime);
    }

    @Bean
    ReviewRequestApplicationService reviewRequestApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            DiffArtifactRepository diffs,
            TestEvidenceRepository tests,
            CommandEvidenceRepository commands,
            PolicySnapshotRepository policies,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            TeamMembershipQuery memberships,
            ResponsibilityAssignmentRepository assignments,
            ReviewSubjectRepository subjects,
            ContextPackageRepository contexts,
            ReviewRequestRepository requests,
            ReviewFindingRepository findings,
            ReviewDecisionRepository decisions,
            ReviewModificationRoundRepository rounds,
            ReviewQueryRepository queries,
            ContextPackageBuilder contextBuilder,
            ReviewEventPublisher reviewEvents,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ReviewRequestApplicationService(
                accessPolicy, tasks, executions, diffs, tests, commands, policies,
                principals, profiles, memberships, assignments, subjects, contexts, requests,
                findings, decisions, rounds, queries, contextBuilder, reviewEvents,
                receipts, transactions, timeProvider);
    }

    @Bean
    ReviewerExecutionApplicationService reviewerExecutionApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            ReviewRequestRepository requests,
            ContextPackageRepository contexts,
            PolicySnapshotRepository policies,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            TeamMembershipQuery memberships,
            ResponsibilityAssignmentRepository assignments,
            TaskAgentRuntimeSessionRepository sessions,
            ReviewerExecutionPort runtime,
            ReviewFindingBatchRecorder recorder,
            ReviewEventPublisher reviewEvents,
            ReviewQueryRepository queries,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ReviewerExecutionApplicationService(
                accessPolicy, tasks, executions, requests, contexts, policies,
                principals, profiles, memberships, assignments, sessions, runtime,
                recorder, reviewEvents, queries, receipts, transactions, timeProvider);
    }

    @Bean
    ReviewGateApplicationService reviewGateApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            TeamMembershipQuery memberships,
            ResponsibilityAssignmentRepository assignments,
            GateReviewerPolicyProvider policies,
            ReviewRequestRepository requests,
            ContextPackageRepository contexts,
            ReviewDecisionRepository decisions,
            ReviewModificationRoundRepository rounds,
            ReviewQueryRepository queries,
            ReviewEventPublisher reviewEvents,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ReviewGateApplicationService(
                accessPolicy, tasks, executions, memberships, assignments, policies,
                requests, contexts, decisions, rounds, queries, reviewEvents,
                receipts, transactions, timeProvider);
    }
}

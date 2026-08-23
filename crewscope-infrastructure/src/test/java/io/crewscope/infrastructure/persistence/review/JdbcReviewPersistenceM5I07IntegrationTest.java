package io.crewscope.infrastructure.persistence.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingId;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewFindingObservationId;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewModificationRoundId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.review.DurableReviewEventPublisher;
import io.crewscope.application.review.ReviewEventPublisher;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.event.FinalDiffArtifactPublished;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.event.JdbcOutboxClaimStore;
import io.crewscope.infrastructure.event.OutboxPublisherConfiguration;
import io.crewscope.infrastructure.event.PollingOutboxPublisher;
import io.crewscope.infrastructure.event.projection.AuditEventProjector;
import io.crewscope.infrastructure.event.projection.JdbcProjectionCheckpointStore;
import io.crewscope.infrastructure.event.projection.ProjectionConfiguration;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcTaskEventRepository;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** M5-I07 PostgreSQL proof for Review recovery, concurrency and query projection rebuild. */
@SpringBootTest(
        classes = JdbcReviewPersistenceM5I07IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false"
        })
class JdbcReviewPersistenceM5I07IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcReviewRepositoryAdapter reviews;

    @Autowired
    private JdbcReviewQueryRepositoryAdapter queries;

    @Autowired
    private ReviewEventPublisher reviewEvents;

    @Autowired
    private DomainEventStore domainEvents;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private TaskEventRepository taskEvents;

    @Autowired
    private TransactionExecutor transactions;

    @Autowired
    private PollingOutboxPublisher outboxPublisher;

    private ReviewPersistenceTestFixture fixture;

    @BeforeEach
    void seedReviewAuthority() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        fixture = new ReviewPersistenceTestFixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Review Org', 'ACTIVE')",
                    fixture.scope.organizationId().value());
            jdbc.update(
                    "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Review Team', 'ACTIVE')",
                    fixture.scope.teamId().value(), fixture.scope.organizationId().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.workspace (
                        id, organization_id, team_id, workspace_type, name, status
                    ) VALUES (?, ?, ?, 'TEAM', 'Review Workspace', 'ACTIVE')
                    """,
                    fixture.scope.workspaceId().value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.work_project (
                        id, organization_id, team_id, workspace_id, project_key, name
                    ) VALUES (?, ?, ?, ?, 'REVIEW', 'Review Project')
                    """,
                    fixture.scope.projectId().value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value(), fixture.scope.workspaceId().value());
            insertPrincipal(fixture.actor.id().value(), "USER", "Review owner");
            insertPrincipal(
                    fixture.reviewerAgent.id().value(), "SPECIALIST_AGENT", "Reviewer Specialist");
            reviews.save(fixture.subject);
            reviews.save(fixture.context);
            reviews.insert(fixture.runningRequest);
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_request_state (
                        review_request_id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, revision, request_version,
                        request_hash, status, recorded_at, recorded_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 1, ?, 'IN_PROGRESS', ?, ?)
                    """,
                    fixture.runningRequest.id().value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value(), fixture.scope.workspaceId().value(),
                    fixture.scope.projectId().value(), fixture.taskId.value(),
                    fixture.executionId.value(), fixture.runningRequest.requestHash().value(),
                    fixture.runningRequest.audit().updatedAt().toOffsetDateTime(),
                    fixture.actor.id().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.test_acceptance_result (
                        test_evidence_id, criterion_index, criterion, status, summary
                    ) VALUES (?, 1, 'Return an empty value when name is null',
                        'PASSED', 'Criterion passed')
                    """,
                    fixture.testEvidence.id().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.task (
                        id, organization_id, team_id, workspace_id, project_id, work_item_id,
                        source_type, source_work_item_version, responsibility_snapshot_id,
                        status, current_execution_id, version, objective, acceptance_criteria,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'ACTIVE', ?, 0,
                        'Review persisted delivery', '[]'::JSONB, ?, ?, ?, ?)
                    """,
                    fixture.taskId.value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value(), fixture.scope.workspaceId().value(),
                    fixture.scope.projectId().value(), UUID.randomUUID(), UUID.randomUUID(),
                    fixture.executionId.value(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    fixture.actor.id().value(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    fixture.actor.id().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.task_execution (
                        id, organization_id, team_id, workspace_id, project_id, task_id,
                        attempt, max_attempts, priority, not_before, status,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, 3, 50, ?, 'CREATED', ?, ?, ?, ?)
                    """,
                    fixture.executionId.value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value(), fixture.scope.workspaceId().value(),
                    fixture.scope.projectId().value(), fixture.taskId.value(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    fixture.actor.id().value(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    fixture.actor.id().value());
        });
    }

    @Test
    void roundTripsHashClosedFactsAndRejectsCrossTenantQueries() {
        assertEquals(fixture.subject.subjectHash(), reviews.findById(
                fixture.scope.organizationId(), fixture.subject.id()).orElseThrow().subjectHash());
        var restoredContext = reviews.findById(
                fixture.scope.organizationId(), fixture.context.id()).orElseThrow();
        assertEquals(fixture.context.contextHash(), restoredContext.contextHash());
        assertEquals(fixture.context.hunks().get(0).patch(), restoredContext.hunks().get(0).patch());
        assertEquals(fixture.context.testEvidence(), restoredContext.testEvidence());
        assertEquals(fixture.runningRequest.requestHash(), reviews.findById(
                fixture.scope.organizationId(), fixture.runningRequest.id())
                .orElseThrow().requestHash());

        OrganizationId otherOrganization = OrganizationId.generate();
        assertTrue(reviews.findById(otherOrganization, fixture.subject.id()).isEmpty());
        assertTrue(reviews.findById(otherOrganization, fixture.context.id()).isEmpty());
        assertTrue(reviews.findById(otherOrganization, fixture.runningRequest.id()).isEmpty());
        assertTrue(queries.findByRequest(otherOrganization, fixture.runningRequest.id()).isEmpty());
    }

    @Test
    void rejectsContextScalarAndChildProjectionDrift() {
        String storedRelationship = fixture.context.reviewer().relationship().name();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            if ("INDEPENDENT".equals(storedRelationship)) {
                jdbc.update(
                        """
                        UPDATE crewscope.review_context_package
                        SET reviewer_relationship = 'SELF_REVIEW',
                            reviewer_owner_member_id = subject_owner_member_id
                        WHERE id = ?
                        """,
                        fixture.context.id().value());
            } else {
                jdbc.update(
                        """
                        UPDATE crewscope.review_context_package
                        SET reviewer_relationship = 'INDEPENDENT', reviewer_owner_member_id = NULL
                        WHERE id = ?
                        """,
                        fixture.context.id().value());
            }
        });
        assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> reviews.findById(
                fixture.scope.organizationId(), fixture.context.id()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    """
                    UPDATE crewscope.review_context_package
                    SET reviewer_relationship = ?, reviewer_owner_member_id = ?
                    WHERE id = ?
                    """,
                    storedRelationship,
                    fixture.context.reviewer().reviewerOwnerMemberId()
                            .map(memberId -> memberId.value()).orElse(null),
                    fixture.context.id().value());
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    """
                    UPDATE crewscope.review_context_hunk
                    SET patch_bytes = patch_bytes + 1 WHERE context_package_id = ?
                    """,
                    fixture.context.id().value());
        });
        assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> reviews.findById(
                fixture.scope.organizationId(), fixture.context.id()));
    }

    @Test
    void recordsFindingObservationAndMaintainsProjectionAndOptimisticStateHistory() {
        ReviewFinding finding = finding("Null behavior is inconsistent");
        reviews.insert(finding);
        assertEquals(finding.fingerprint(), reviews.findById(
                fixture.scope.organizationId(), finding.id()).orElseThrow().fingerprint());

        ReviewFindingObservation duplicate = ReviewFindingObservation.duplicate(
                ReviewFindingObservationId.generate(),
                2,
                finding,
                fixture.runningRequest,
                fixture.context,
                fixture.finding("Equivalent wording", io.crewscope.domain.review.FindingSeverity.HIGH),
                fixture.runningRequest.version(),
                fixture.reviewerAgent,
                ReviewPersistenceTestFixture.LATER);
        ReviewFindingObservation committed = reviews.append(duplicate);
        assertEquals(2, committed.observationNumber());

        var projection = queries.findByRequest(
                fixture.scope.organizationId(), fixture.runningRequest.id()).orElseThrow();
        assertEquals(1, projection.findingCount());
        assertEquals(1, projection.duplicateObservationCount());
        assertEquals(1, projection.highCount());

        ReviewRequest completed = fixture.runningRequest.complete(
                fixture.context,
                fixture.runningRequest.version(),
                fixture.actor,
                ReviewPersistenceTestFixture.LATER);
        reviews.update(completed, fixture.runningRequest.version());
        assertEquals(ReviewRequestStatus.COMPLETED, queries.findByRequest(
                fixture.scope.organizationId(), completed.id()).orElseThrow().status());
        assertEquals(1, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.review_request_state
                WHERE review_request_id = ? AND request_version = 2
                """,
                Integer.class,
                completed.id().value()));
        assertThrows(OptimisticLockConflictException.class, () ->
                reviews.update(completed, fixture.runningRequest.version()));
    }

    @Test
    void concurrentFindingInsertKeepsOneWinnerAndConcurrentObservationsStayContinuous()
            throws Exception {
        ReviewFinding first = finding("Concurrent candidate A");
        ReviewFinding second = finding("Concurrent candidate B");
        assertNotEquals(first.id(), second.id());
        assertEquals(first.fingerprint(), second.fingerprint());

        List<ReviewFinding> winners;
        var findingExecutor = Executors.newFixedThreadPool(2);
        try {
            winners = findingExecutor.invokeAll(List.<Callable<ReviewFinding>>of(
                            () -> reviews.insertOrFind(first),
                            () -> reviews.insertOrFind(second)))
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
        } finally {
            findingExecutor.shutdownNow();
        }
        assertEquals(winners.get(0).id(), winners.get(1).id());
        ReviewFinding winner = winners.get(0);
        assertEquals(1, reviews.findAllByRequest(
                fixture.scope.organizationId(), fixture.runningRequest.id()).size());

        ReviewFindingObservation observationA = duplicate(winner, "Observation A");
        ReviewFindingObservation observationB = duplicate(winner, "Observation B");
        var observationExecutor = Executors.newFixedThreadPool(2);
        try {
            observationExecutor.invokeAll(List.<Callable<ReviewFindingObservation>>of(
                            () -> reviews.append(observationA),
                            () -> reviews.append(observationB)))
                    .forEach(future -> {
                        try {
                            future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        } finally {
            observationExecutor.shutdownNow();
        }
        assertEquals(List.of(2L, 3L), reviews.findAllByFinding(
                        fixture.scope.organizationId(), winner.id()).stream()
                .map(ReviewFindingObservation::observationNumber)
                .toList());
    }

    @Test
    void deterministicallyRebuildsProjectionFromAuthoritativeFacts() {
        reviews.insert(finding("Projection source"));
        jdbc.update(
                "DELETE FROM crewscope.review_request_projection WHERE organization_id = ?",
                fixture.scope.organizationId().value());
        assertTrue(queries.findByRequest(
                fixture.scope.organizationId(), fixture.runningRequest.id()).isEmpty());

        assertEquals(1, queries.rebuildAll(fixture.scope.organizationId()));
        var rebuilt = queries.findByRequest(
                fixture.scope.organizationId(), fixture.runningRequest.id()).orElseThrow();
        assertEquals(1, rebuilt.findingCount());
        assertEquals(fixture.context.contextHash(), rebuilt.contextHash());
        assertFalse(queries.findHistoryByTask(
                fixture.scope.organizationId(), fixture.taskId, 20).isEmpty());
        assertEquals(1, queries.findByExecution(
                fixture.scope.organizationId(), fixture.executionId, 1).size());
    }

    @Test
    void roundTripsMemberDecisionAndModificationRoundWithProjection() {
        ReviewRequest completed = fixture.runningRequest.complete(
                fixture.context,
                fixture.runningRequest.version(),
                fixture.actor,
                ReviewPersistenceTestFixture.LATER);
        reviews.update(completed, fixture.runningRequest.version());

        Principal gateReviewer = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(fixture.scope.organizationId(), fixture.scope.teamId()),
                PrincipalType.USER,
                java.util.Optional.empty(),
                "Gate reviewer",
                java.util.Optional.empty(),
                PrincipalVisibility.TEAM,
                ReviewPersistenceTestFixture.CREATED_AT);
        TeamMember ownerMember = TeamMember.join(
                TeamMemberId.generate(),
                new TeamScope(fixture.scope.organizationId(), fixture.scope.teamId()),
                fixture.actor,
                TeamJoinMethod.BOOTSTRAP,
                ReviewPersistenceTestFixture.CREATED_AT);
        TeamMember gateMember = TeamMember.join(
                TeamMemberId.generate(),
                new TeamScope(fixture.scope.organizationId(), fixture.scope.teamId()),
                gateReviewer,
                TeamJoinMethod.OIDC,
                ReviewPersistenceTestFixture.CREATED_AT);
        Principal codingAgent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(fixture.scope.organizationId(), fixture.scope.teamId()),
                PrincipalType.SPECIALIST_AGENT,
                java.util.Optional.of(fixture.actor.id()),
                "Coding Specialist",
                java.util.Optional.empty(),
                PrincipalVisibility.TEAM,
                ReviewPersistenceTestFixture.CREATED_AT);
        WorkItem workItem = WorkItem.reconstitute(
                WorkItemId.generate(),
                fixture.scope,
                new WorkItemKey("CRW-1"),
                "Review code delivery",
                WorkItemStatus.IN_REVIEW,
                3,
                AuditMetadata.createdBy(
                        fixture.actor.id(), ReviewPersistenceTestFixture.CREATED_AT));
        ResponsibilityAssignment ownerAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(), workItem, ResponsibilityRole.OWNER,
                fixture.actor, java.util.Optional.of(ownerMember), fixture.actor,
                ReviewPersistenceTestFixture.CREATED_AT);
        ResponsibilityAssignment executorAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(), workItem, ResponsibilityRole.EXECUTOR,
                codingAgent, java.util.Optional.empty(), fixture.actor,
                ReviewPersistenceTestFixture.CREATED_AT);
        ResponsibilityAssignment gateAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(), workItem, ResponsibilityRole.REVIEWER,
                gateReviewer, java.util.Optional.of(gateMember), fixture.actor,
                ReviewPersistenceTestFixture.CREATED_AT);
        List<ResponsibilityAssignment> assignments =
                List.of(ownerAssignment, executorAssignment, gateAssignment);
        Task task = Task.create(
                        fixture.taskId,
                        workItem,
                        TaskSource.fromWorkItem(workItem),
                        TaskResponsibilitySnapshot.capture(
                                workItem, assignments, ReviewPersistenceTestFixture.CREATED_AT),
                        fixture.actor,
                        ReviewPersistenceTestFixture.CREATED_AT)
                .switchCurrentExecution(
                        java.util.Optional.empty(), fixture.executionId, 0,
                        fixture.actor, ReviewPersistenceTestFixture.LATER);
        seedGateReferences(workItem, gateReviewer, gateMember);

        ReviewDecision decision = ReviewDecision.initial(
                ReviewDecisionId.generate(),
                completed,
                fixture.context,
                task,
                workItem,
                ReviewDecisionType.CHANGES_REQUESTED,
                "Add one regression guard",
                completed.version(),
                ReviewerEligibilityPolicy.strict(),
                gateReviewer,
                gateMember,
                List.of(ownerMember, gateMember),
                assignments,
                ReviewPersistenceTestFixture.LATER);
        reviews.insert(decision);
        ReviewDecision restored = reviews.findById(
                fixture.scope.organizationId(), decision.id()).orElseThrow();
        assertEquals(decision.decisionHash(), restored.decisionHash());
        assertEquals(decision.eligibility(), restored.eligibility());

        ReviewModificationRound round = ReviewModificationRound.initial(
                ReviewModificationRoundId.generate(),
                decision,
                gateReviewer,
                ReviewPersistenceTestFixture.LATER);
        reviews.insert(round);
        assertEquals(round.roundHash(), reviews.findById(
                fixture.scope.organizationId(), round.id()).orElseThrow().roundHash());
        assertEquals(1, reviews.findDecisionsByRequest(
                fixture.scope.organizationId(), completed.id()).size());
        assertEquals(1, reviews.findAllByTask(
                fixture.scope.organizationId(), fixture.taskId).size());
        assertEquals(1, queries.findByRequest(
                fixture.scope.organizationId(), completed.id()).orElseThrow().modificationRound());
    }

    @Test
    void publishesReviewFactsThroughOutboxIntoTaskTimelineAndAudit() {
        ReviewFinding finding = finding("Publish safe Review event");
        reviews.insert(finding);

        reviewEvents.findingRecorded(
                finding,
                EventActor.principal(EventActorType.SPECIALIST_AGENT, fixture.reviewerAgent.id()),
                UUID.randomUUID());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'REVIEW_FINDING_RECORDED'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.task_event WHERE task_id = ?",
                Integer.class,
                fixture.taskId.value()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.outbox_event WHERE delivery_status = 'PENDING'",
                Integer.class));

        assertEquals(1, outboxPublisher.publishAvailable().delivered());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.audit_event WHERE event_type = 'REVIEW_FINDING_RECORDED'",
                Integer.class));
        assertEquals(ReviewRequestStatus.IN_PROGRESS, reviews.findById(
                fixture.scope.organizationId(), fixture.runningRequest.id())
                .orElseThrow()
                .status());
        String payload = jdbc.queryForObject(
                """
                SELECT payload::TEXT FROM crewscope.domain_event
                WHERE event_type = 'REVIEW_FINDING_RECORDED'
                """,
                String.class);
        assertFalse(payload.contains("suggestedFix"));
        assertFalse(payload.contains("claim"));
        assertFalse(payload.contains("patch"));
    }

    @Test
    void rollsBackReviewFactAndEventRowsWhenOutboxCreationFails() {
        ReviewFinding finding = finding("Atomic Review publication");
        OutboxRepository failingOutbox = ignored -> {
            throw new SimulatedOutboxFailure();
        };
        ReviewEventPublisher failingPublisher = new DurableReviewEventPublisher(
                domainEvents, taskEvents, failingOutbox, transactions);

        assertThrows(SimulatedOutboxFailure.class, () -> transactions.required(() -> {
            reviews.insert(finding);
            failingPublisher.findingRecorded(
                    finding,
                    EventActor.principal(
                            EventActorType.SPECIALIST_AGENT, fixture.reviewerAgent.id()),
                    UUID.randomUUID());
            return null;
        }));

        assertTrue(reviews.findById(fixture.scope.organizationId(), finding.id()).isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'REVIEW_FINDING_RECORDED'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.task_event WHERE task_id = ?",
                Integer.class,
                fixture.taskId.value()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.outbox_event",
                Integer.class));
        assertEquals(0, queries.findByRequest(
                fixture.scope.organizationId(), fixture.runningRequest.id())
                .orElseThrow()
                .findingCount());
    }

    @Test
    void finalDiffEventInvalidatesCurrentRequestAndPublishesSafeFollowUp() {
        var nextDiff = fixture.diff("next-diff", 2);
        FinalDiffArtifactPublished payload = new FinalDiffArtifactPublished(
                nextDiff.artifact().id().value(),
                UUID.randomUUID(),
                fixture.executionId.value(),
                1,
                2,
                nextDiff.manifestHash().value(),
                1,
                1,
                0,
                nextDiff.artifact().finalHash().value());
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<FinalDiffArtifactPublished> envelope = new DomainEventEnvelope<>(
                eventId,
                EventType.from("FINAL_DIFF_ARTIFACT_PUBLISHED"),
                SchemaVersion.V1,
                fixture.scope.organizationId(),
                Optional.of(fixture.scope.teamId()),
                Optional.of(fixture.scope.workspaceId()),
                new AggregateReference("DIFF_ARTIFACT", nextDiff.artifact().id().value()),
                0,
                EventActor.anonymousService(),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.of("m5-i07-diff:" + eventId),
                ReviewPersistenceTestFixture.LATER,
                payload);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            domainEvents.append(envelope);
            outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), envelope));
        });

        assertEquals(1, outboxPublisher.publishAvailable().delivered());
        ReviewRequest invalidated = reviews.findById(
                fixture.scope.organizationId(), fixture.runningRequest.id()).orElseThrow();
        assertEquals(ReviewRequestStatus.INVALIDATED, invalidated.status());
        assertEquals("DIFF_CHANGED", invalidated.invalidationReason().orElseThrow().name());
        assertEquals(ReviewRequestStatus.INVALIDATED, queries.findByRequest(
                fixture.scope.organizationId(), invalidated.id()).orElseThrow().status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'REVIEW_REQUEST_INVALIDATED'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.outbox_event outbox
                JOIN crewscope.domain_event event ON event.event_id = outbox.domain_event_id
                WHERE event.event_type = 'REVIEW_REQUEST_INVALIDATED'
                  AND outbox.delivery_status = 'PENDING'
                """,
                Integer.class));

        assertEquals(1, outboxPublisher.publishAvailable().delivered());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.audit_event WHERE event_type = 'REVIEW_REQUEST_INVALIDATED'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.review_request_state
                WHERE review_request_id = ? AND status = 'INVALIDATED'
                """,
                Integer.class,
                invalidated.id().value()));
    }

    private ReviewFinding finding(String title) {
        return ReviewFinding.record(
                ReviewFindingId.generate(),
                fixture.runningRequest,
                fixture.context,
                fixture.finding(title, io.crewscope.domain.review.FindingSeverity.HIGH),
                fixture.runningRequest.version(),
                fixture.reviewerAgent,
                ReviewPersistenceTestFixture.LATER);
    }

    private ReviewFindingObservation duplicate(ReviewFinding winner, String title) {
        return ReviewFindingObservation.duplicate(
                ReviewFindingObservationId.generate(),
                2,
                winner,
                fixture.runningRequest,
                fixture.context,
                fixture.finding(title, io.crewscope.domain.review.FindingSeverity.HIGH),
                fixture.runningRequest.version(),
                fixture.reviewerAgent,
                ReviewPersistenceTestFixture.LATER);
    }

    private void insertPrincipal(UUID id, String type, String name) {
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                id, fixture.scope.organizationId().value(), type, name);
    }

    private void seedGateReferences(
            WorkItem workItem, Principal gateReviewer, TeamMember gateMember) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertPrincipal(gateReviewer.id().value(), "USER", "Gate reviewer");
            jdbc.update(
                    """
                    INSERT INTO crewscope.team_member (
                        id, organization_id, team_id, user_principal_id, status,
                        join_method, joined_at, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', 'OIDC', ?, 0, ?, ?)
                    """,
                    gateMember.id().value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value(), gateReviewer.id().value(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.work_item (
                        id, organization_id, team_id, workspace_id, project_id,
                        item_key, item_type, title, status, priority, source_provider,
                        version, labels, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'CRW-1', 'TASK', 'Review code delivery',
                        'IN_REVIEW', 'MEDIUM', 'CREWSCOPE', 3, '[]'::JSONB, ?, ?)
                    """,
                    workItem.id().value(), fixture.scope.organizationId().value(),
                    fixture.scope.teamId().value(), fixture.scope.workspaceId().value(),
                    fixture.scope.projectId().value(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime(),
                    ReviewPersistenceTestFixture.CREATED_AT.toOffsetDateTime());
        });
    }

    /** Explicit failure used to prove the Review transaction is all-or-nothing. */
    private static final class SimulatedOutboxFailure extends RuntimeException {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        ReviewContextAuthorityJsonCodec.class,
        JdbcReviewQueryRepositoryAdapter.class,
        JdbcReviewRepositoryAdapter.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        JdbcTaskEventRepository.class,
        SpringTransactionExecutor.class,
        ReviewPersistenceConfiguration.class,
        JdbcOutboxClaimStore.class,
        JdbcProjectionCheckpointStore.class,
        AuditEventProjector.class,
        ProjectionConfiguration.class,
        OutboxPublisherConfiguration.class
    })
    static class TestApplication {}
}

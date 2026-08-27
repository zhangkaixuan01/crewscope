package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.inbox.CrewScopeInboxEventTypes;
import io.crewscope.application.inbox.InboxCursor;
import io.crewscope.application.inbox.InboxCursorExpiredException;
import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.application.inbox.InboxItemView;
import io.crewscope.application.inbox.InboxPage;
import io.crewscope.application.inbox.InboxQuery;
import io.crewscope.application.inbox.InboxSourceTarget;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.inbox.JdbcInboxRepositoryAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL contract for M6-E03's Generation-aware member Inbox and disposition merge. */
@SpringBootTest(
        classes = InboxEventProjectorM6E03IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class InboxEventProjectorM6E03IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-26T02:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String BASE_COMMIT = "b".repeat(40);
    private static final String DELIVERY_COMMIT = "c".repeat(40);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private UUID teamId;
    private UUID workspaceId;
    private UUID principalId;
    private UUID memberId;
    private UUID projectId;
    private UUID workItemId;
    private InboxEventProjector projector;
    private GenerationAwareProjectionRunner runner;
    private ProjectionHistoryReplayer replayer;
    private JdbcInboxRepositoryAdapter repository;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                InboxEventProjector.PROJECTION_NAME.value());
        seedScope();
        projector = new InboxEventProjector(
                jdbc,
                objectMapper,
                CrewScopeInboxEventTypes.reviewedRegistry(),
                mock(NotificationIntentProjector.class));
        JdbcProjectionGenerationRegistry registry =
                new JdbcProjectionGenerationRegistry(jdbc, transactionManager);
        JdbcGenerationProjectionStore store = new JdbcGenerationProjectionStore(jdbc);
        ProjectionEventJsonMapper mapper = new ProjectionEventJsonMapper(objectMapper);
        runner = new GenerationAwareProjectionRunner(
                projector, registry, store, mapper, transactionManager,
                Clock.fixed(BASE_TIME.plusSeconds(300), ZoneOffset.UTC));
        replayer = new ProjectionHistoryReplayer(
                new JdbcProjectionEventHistoryStore(jdbc, objectMapper), runner);
        repository = new JdbcInboxRepositoryAdapter(jdbc);
    }

    @Test
    void ownerExecutorReleaseAndDuplicateReplayConvergeToStableSources() {
        UUID ownerAssignment = seedAssignment("OWNER", BASE_TIME);
        UUID executorAssignment = seedAssignment("EXECUTOR", BASE_TIME.plusSeconds(1));
        UUID ownerAssigned = seedEvent(
                "WORK_ITEM_OWNER_ASSIGNED", ownerAssignment, 0, BASE_TIME,
                responsibilityPayload("OWNER", Optional.empty()));
        UUID executorAssigned = seedEvent(
                "WORK_ITEM_EXECUTOR_ASSIGNED", executorAssignment, 0,
                BASE_TIME.plusSeconds(1), responsibilityPayload("EXECUTOR", Optional.empty()));

        runner.consume(publication(ownerAssigned));
        runner.consume(publication(ownerAssigned));
        runner.consume(publication(executorAssigned));
        release(ownerAssignment, BASE_TIME.plusSeconds(2));
        UUID released = seedEvent(
                "WORK_ITEM_RESPONSIBILITY_RELEASED", ownerAssignment, 1,
                BASE_TIME.plusSeconds(2), responsibilityPayload("OWNER", Optional.empty()));
        runner.consume(publication(released));

        assertEquals(2, itemCount(ProjectionGeneration.FIRST));
        assertEquals(1, countBy("OWNERSHIP", "CLOSED"));
        assertEquals(1, countBy("EXECUTION", "OPEN"));
        assertEquals("RESPONSIBILITY_RELEASED", jdbc.queryForObject(
                "SELECT close_reason FROM crewscope.inbox_item WHERE item_type = 'OWNERSHIP'",
                String.class));
        assertEquals(3, receiptCount(ProjectionGeneration.FIRST));
        assertEquals(
                projector.expectedSnapshot(organizationId),
                projector.actualSnapshot(lease(ProjectionGeneration.FIRST).key()));
    }

    @Test
    void memberQueueProvidesStableKeysetCountsAndServerResolvedTarget() {
        UUID ownerAssignment = seedAssignment("OWNER", BASE_TIME);
        UUID executorAssignment = seedAssignment("EXECUTOR", BASE_TIME.plusSeconds(1));
        runner.consume(publication(seedEvent(
                "WORK_ITEM_OWNER_ASSIGNED", ownerAssignment, 0, BASE_TIME,
                responsibilityPayload("OWNER", Optional.empty()))));
        runner.consume(publication(seedEvent(
                "WORK_ITEM_EXECUTOR_ASSIGNED", executorAssignment, 0,
                BASE_TIME.plusSeconds(1), responsibilityPayload("EXECUTOR", Optional.empty()))));

        InboxQuery firstQuery = new InboxQuery(
                organizationId,
                new TeamId(teamId),
                new io.crewscope.domain.team.TeamMemberId(memberId),
                InboxFilter.OPEN,
                Optional.empty(),
                1);
        InboxPage first = repository.findCurrentPage(firstQuery);
        InboxCursor cursor = first.nextCursor().orElseThrow();
        InboxPage second = repository.findCurrentPage(new InboxQuery(
                firstQuery.organizationId(),
                firstQuery.teamId(),
                firstQuery.memberId(),
                firstQuery.filter(),
                Optional.of(cursor),
                1));

        assertEquals(1, first.items().size());
        assertEquals(1, second.items().size());
        assertEquals(2, repository.countCurrent(
                organizationId, new TeamId(teamId), firstQuery.memberId()).total());
        assertEquals(2, repository.countCurrent(
                organizationId, new TeamId(teamId), firstQuery.memberId()).unread());
        InboxSourceTarget target = repository.resolveCurrentTarget(
                        organizationId,
                        new TeamId(teamId),
                        firstQuery.memberId(),
                        first.items().get(0).item().id())
                .orElseThrow();
        assertEquals(InboxSourceTarget.Kind.WORK_ITEM, target.kind());
        assertEquals(workItemId, target.workItemId().orElseThrow().value());
    }

    @Test
    void oldAssignmentDeliveredAfterReleaseCannotReopenMemberWork() {
        UUID assignmentId = seedAssignment("OWNER", BASE_TIME);
        release(assignmentId, BASE_TIME.plusSeconds(2));
        UUID old = seedEvent(
                "WORK_ITEM_OWNER_ASSIGNED", assignmentId, 0, BASE_TIME,
                responsibilityPayload("OWNER", Optional.empty()));

        runner.consume(publication(old));

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT source_status FROM crewscope.inbox_item", String.class));
        assertEquals("RESPONSIBILITY_RELEASED", jdbc.queryForObject(
                "SELECT close_reason FROM crewscope.inbox_item", String.class));
    }

    @Test
    void memberIneligibilityClosesOpenSourcesWithStableReason() {
        UUID assignmentId = seedAssignment("OWNER", BASE_TIME);
        UUID eventId = seedEvent(
                "WORK_ITEM_OWNER_ASSIGNED", assignmentId, 0, BASE_TIME,
                responsibilityPayload("OWNER", Optional.empty()));
        runner.consume(publication(eventId));
        jdbc.update(
                """
                UPDATE crewscope.team_member
                SET status = 'LEFT', version = version + 1, updated_at = ?
                WHERE id = ?
                """,
                BASE_TIME.plusSeconds(5).atOffset(ZoneOffset.UTC), memberId);

        assertEquals(1, projector.reconcileCurrentEligibility(
                lease(ProjectionGeneration.FIRST), new TeamId(teamId)));

        assertEquals("MEMBER_NO_LONGER_ELIGIBLE", jdbc.queryForObject(
                "SELECT close_reason FROM crewscope.inbox_item", String.class));
        assertEquals(
                projector.expectedSnapshot(organizationId),
                projector.actualSnapshot(lease(ProjectionGeneration.FIRST).key()));
    }

    @Test
    void shadowRebuildRetainsReadDispositionAndMergedQueryUsesCurrentPointer() {
        UUID assignmentId = seedAssignment("OWNER", BASE_TIME);
        UUID eventId = seedEvent(
                "WORK_ITEM_OWNER_ASSIGNED", assignmentId, 0, BASE_TIME,
                responsibilityPayload("OWNER", Optional.empty()));
        runner.consume(publication(eventId));
        InboxItemId itemId = new InboxItemId(jdbc.queryForObject(
                "SELECT inbox_item_id FROM crewscope.inbox_item", UUID.class));
        InboxItem item = repository.findCurrent(
                organizationId, new TeamId(teamId), itemId).orElseThrow();
        InboxItemView unread = repository.findCurrentView(
                organizationId, new TeamId(teamId), itemId).orElseThrow();
        assertEquals(InboxDispositionStatus.UNREAD, unread.dispositionStatus());
        assertEquals(0, unread.dispositionVersion());
        InboxDisposition read = InboxDisposition.create(
                item, InboxDispositionStatus.READ, 0, new PrincipalId(principalId),
                UtcTimestamp.from(BASE_TIME.plusSeconds(10)));
        repository.save(read, 0);
        InboxCursor oldCursor = InboxCursor.from(repository.findCurrentPage(new InboxQuery(
                        organizationId,
                        new TeamId(teamId),
                        item.memberId(),
                        InboxFilter.OPEN,
                        Optional.empty(),
                        1))
                .items().get(0));

        Shadow shadow = startShadow();
        Optional<ProjectionHistoryCursor> cursor = Optional.empty();
        while (true) {
            ProjectionReplayBatchResult replayed = replayer.replayPage(shadow.lease(), cursor, 100);
            if (replayed.caughtUp()) {
                break;
            }
            cursor = replayed.nextCursor();
        }
        assertEquals(1, itemCount(new ProjectionGeneration(2)));
        JdbcProjectionGenerationLifecycle lifecycle = new JdbcProjectionGenerationLifecycle(
                // Generation 1 uses PostgreSQL CURRENT_TIMESTAMP during runtime bootstrap.
                jdbc, transactionManager, Clock.systemUTC());
        ProjectionValidationOutcome validation = lifecycle.validate(
                projector,
                new ProjectionValidationRequest(
                        shadow.lease().key(), shadow.jobId(), ProjectionDefinitionVersion.V1,
                        0, 0, new PrincipalId(principalId)));
        lifecycle.switchGeneration(
                projector,
                new ProjectionSwitchRequest(
                        shadow.lease().key(), ProjectionGeneration.FIRST, shadow.jobId(),
                        ProjectionDefinitionVersion.V1, 0, 0,
                        validation.generationVersion(), validation.jobVersion()));

        InboxItemView merged = repository.findCurrentView(
                organizationId, new TeamId(teamId), itemId).orElseThrow();
        assertEquals(2, merged.item().projectionGeneration().value());
        assertEquals(InboxDispositionStatus.READ, merged.dispositionStatus());
        assertEquals(1, merged.dispositionVersion());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.inbox_disposition", Integer.class));
        assertThrows(
                InboxCursorExpiredException.class,
                () -> repository.findCurrentPage(new InboxQuery(
                        organizationId,
                        new TeamId(teamId),
                        item.memberId(),
                        InboxFilter.OPEN,
                        Optional.of(oldCursor),
                        1)));
    }

    @Test
    void reviewLifecycleUsesFrozenReviewerAndLateCreateCannotOpenInvalidatedRequest() {
        ReviewFixture active = seedReview("OPEN", BASE_TIME.plusSeconds(40));
        UUID created = seedEvent(
                "REVIEW_REQUEST_CREATED", active.requestId(), 0, BASE_TIME.plusSeconds(40),
                """
                {"reviewRequestId":"%s","requestRevision":1}
                """.formatted(active.requestId()));
        runner.consume(publication(created));

        assertEquals(1, countBy("REVIEW", "OPEN"));
        assertEquals(memberId, jdbc.queryForObject(
                "SELECT member_id FROM crewscope.inbox_item WHERE item_type = 'REVIEW'",
                UUID.class));

        inReplica(() -> jdbc.update(
                """
                UPDATE crewscope.review_request
                SET status = 'COMPLETED', version = version + 1, updated_at = ?
                WHERE id = ?
                """,
                BASE_TIME.plusSeconds(41).atOffset(ZoneOffset.UTC), active.requestId()));
        UUID completed = seedEvent(
                "REVIEW_REQUEST_COMPLETED", active.requestId(), 1,
                BASE_TIME.plusSeconds(41),
                """
                {"reviewRequestId":"%s"}
                """.formatted(active.requestId()));
        runner.consume(publication(completed));

        assertEquals("REVIEW_COMPLETED", closeReason("REVIEW", active.requestId()));

        ReviewFixture invalidated = seedReview("INVALIDATED", BASE_TIME.plusSeconds(42));
        UUID lateCreate = seedEvent(
                "REVIEW_REQUEST_CREATED", invalidated.requestId(), 0,
                BASE_TIME.plusSeconds(42),
                """
                {"reviewRequestId":"%s","requestRevision":1}
                """.formatted(invalidated.requestId()));
        runner.consume(publication(lateCreate));

        assertEquals("REVIEW_SUPERSEDED", closeReason("REVIEW", invalidated.requestId()));
    }

    @Test
    void confirmationLifecycleUsesFrozenOwnerAndLatePlanHonorsCancellation() {
        UUID assignmentId = seedAssignment("OWNER", BASE_TIME.plusSeconds(50));
        ActionBundleFixture active = seedActionBundle(assignmentId, BASE_TIME.plusSeconds(50));
        UUID planned = seedEvent(
                "ACTION_BUNDLE_PLANNED", active.bundleId(), 0,
                BASE_TIME.plusSeconds(50), actionBundlePayload(active.bundleId()));
        runner.consume(publication(planned));

        assertEquals(1, countBy("CONFIRMATION", "OPEN"));
        assertEquals(memberId, jdbc.queryForObject(
                "SELECT member_id FROM crewscope.inbox_item WHERE item_type = 'CONFIRMATION'",
                UUID.class));

        seedConfirmation(active, "ACTIVE", BASE_TIME.plusSeconds(51));
        UUID confirmed = seedEvent(
                "ACTION_BUNDLE_CONFIRMED", active.bundleId(), 1,
                BASE_TIME.plusSeconds(51),
                """
                {"actionBundleId":"%s"}
                """.formatted(active.bundleId()));
        runner.consume(publication(confirmed));

        assertEquals("CONFIRMATION_COMPLETED",
                closeReason("CONFIRMATION", active.bundleId()));

        ActionBundleFixture cancelled = seedActionBundle(
                assignmentId, BASE_TIME.plusSeconds(52));
        seedConfirmation(cancelled, "CANCELLED", BASE_TIME.plusSeconds(53));
        UUID latePlan = seedEvent(
                "ACTION_BUNDLE_PLANNED", cancelled.bundleId(), 0,
                BASE_TIME.plusSeconds(52), actionBundlePayload(cancelled.bundleId()));
        runner.consume(publication(latePlan));

        assertEquals("CONFIRMATION_CANCELLED",
                closeReason("CONFIRMATION", cancelled.bundleId()));
    }

    @Test
    void taskAndActionExceptionsOpenRecoverAndAvoidSuccessOnlyNoise() {
        UUID assignmentId = seedAssignment("OWNER", BASE_TIME.plusSeconds(60));
        TaskFixture task = seedFailedTask(BASE_TIME.plusSeconds(60));
        UUID failed = seedEvent(
                "WORKER_TASK_FAIL_ACCEPTED", task.failedExecutionId(), 0,
                BASE_TIME.plusSeconds(61),
                """
                {"taskExecutionId":"%s","attempt":1,"operation":"FAIL"}
                """.formatted(task.failedExecutionId()));
        runner.consume(publication(failed));

        assertEquals(1, countBy("EXCEPTION", "OPEN"));
        seedRetryExecution(task, BASE_TIME.plusSeconds(62));
        UUID retry = seedEvent(
                "MEMBER_TASK_RETRY_ACCEPTED", task.taskId(), 0,
                BASE_TIME.plusSeconds(62),
                """
                {"targetExecutionId":"%s","targetAttempt":1,"operation":"RETRY"}
                """.formatted(task.failedExecutionId()));
        runner.consume(publication(retry));

        assertEquals("EXCEPTION_RESOLVED",
                closeReason("EXCEPTION", task.failedExecutionId()));

        ActionBundleFixture bundle = seedActionBundle(
                assignmentId, BASE_TIME.plusSeconds(63));
        UUID confirmationId = seedConfirmation(
                bundle, "ACTIVE", BASE_TIME.plusSeconds(64));
        UUID manualAction = seedActionDispatch(
                bundle, confirmationId, 1, "MANUAL_REVIEW", BASE_TIME.plusSeconds(65));
        UUID manualReview = seedEvent(
                "ACTION_DISPATCH_TRANSITIONED", manualAction, 0,
                BASE_TIME.plusSeconds(65),
                """
                {"plannedActionId":"%s","status":"MANUAL_REVIEW","dispatchVersion":1}
                """.formatted(manualAction));
        runner.consume(publication(manualReview));

        assertEquals(1, openActionExceptionCount());
        resolveDispatch(manualAction, BASE_TIME.plusSeconds(66));
        UUID succeeded = seedEvent(
                "ACTION_RECEIPT_RECORDED", manualAction, 1,
                BASE_TIME.plusSeconds(66),
                """
                {"plannedActionId":"%s","result":"SUCCEEDED"}
                """.formatted(manualAction));
        runner.consume(publication(succeeded));

        assertEquals("EXCEPTION_RESOLVED", closeReason("EXCEPTION", manualAction));

        UUID successOnly = seedActionDispatch(
                bundle, confirmationId, 2, "SUCCEEDED", BASE_TIME.plusSeconds(67));
        UUID successOnlyEvent = seedEvent(
                "ACTION_RECEIPT_RECORDED", successOnly, 0,
                BASE_TIME.plusSeconds(67),
                """
                {"plannedActionId":"%s","result":"SUCCEEDED"}
                """.formatted(successOnly));
        runner.consume(publication(successOnlyEvent));

        assertEquals(1, actionExceptionCount());
    }

    private ReviewFixture seedReview(String status, Instant createdAt) {
        UUID contextId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_context_package (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, package_version,
                        subject_id, subject_type, subject_hash, diff_artifact_id,
                        diff_final_hash, coding_target_snapshot_id, coding_target_revision,
                        coding_target_hash, diff_generation, diff_manifest_hash,
                        test_evidence_id, test_evidence_hash, reviewer_agent_profile_id,
                        reviewer_agent_profile_version, reviewer_agent_principal_id,
                        reviewer_owner_member_id, subject_owner_member_id,
                        reviewer_relationship, reviewer_template_key, reviewer_template_version,
                        reviewer_template_hash, reviewer_configuration_revision,
                        reviewer_configuration_hash, policy_snapshot_id,
                        policy_snapshot_revision, policy_snapshot_hash, context_hash,
                        authority_snapshot, created_at, created_by_principal_id
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, 'CODE_CHANGE', ?, ?, ?, ?, 1,
                        ?, 1, ?, ?, ?, ?, 0, ?, ?, ?, 'SELF_REVIEW', 'reviewer', 1,
                        ?, 1, ?, ?, 1, ?, ?, '{}'::JSONB, ?, ?
                    )
                    """,
                    contextId, organizationId.value(), teamId, workspaceId, projectId,
                    taskId, executionId, UUID.randomUUID(), HASH, UUID.randomUUID(), HASH,
                    UUID.randomUUID(), HASH, HASH, UUID.randomUUID(), HASH, UUID.randomUUID(),
                    principalId, memberId, memberId, HASH, HASH, UUID.randomUUID(), HASH, HASH,
                    createdAt.atOffset(ZoneOffset.UTC), principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_request (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, revision,
                        subject_id, subject_type, subject_hash, context_package_id,
                        context_package_version, context_hash, request_hash, status,
                        invalidation_reason, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, 'CODE_CHANGE', ?, ?, 1, ?, ?, ?,
                        ?, 0, ?, ?, ?, ?
                    )
                    """,
                    requestId, organizationId.value(), teamId, workspaceId, projectId,
                    taskId, executionId, UUID.randomUUID(), HASH, contextId, HASH, HASH,
                    status, status.equals("INVALIDATED") ? "SUBJECT_CHANGED" : null,
                    createdAt.atOffset(ZoneOffset.UTC), principalId,
                    createdAt.atOffset(ZoneOffset.UTC), principalId);
        });
        return new ReviewFixture(requestId);
    }

    private ActionBundleFixture seedActionBundle(UUID assignmentId, Instant createdAt) {
        ActionBundleFixture fixture = new ActionBundleFixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), HASH);
        inReplica(() -> jdbc.update(
                """
                INSERT INTO crewscope.action_bundle (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_decision_id,
                    review_decision_revision, review_decision_type, review_decision_hash,
                    review_request_id, review_request_revision, review_request_version,
                    review_request_hash, review_subject_id, review_subject_hash,
                    review_context_package_id, review_context_hash,
                    review_diff_artifact_id, review_diff_final_hash,
                    responsibility_assignment_id, responsibility_version,
                    responsibility_role, responsibility_principal_id,
                    provider_binding_id, provider_binding_version,
                    provider_definition_id, provider_definition_version,
                    provider_implementation_id, provider_implementation_version,
                    provider_type, provider_execution_identity, connection_id,
                    connection_version, connection_grant_id, connection_grant_version,
                    effective_access_hash, policy_snapshot_id, policy_snapshot_revision,
                    policy_snapshot_hash, safety_overlay_id, safety_overlay_version,
                    safety_overlay_hash, repository_binding_id, repository_binding_version,
                    repository_key, default_branch, coding_target_snapshot_id,
                    coding_target_revision, coding_target_hash, baseline_commit,
                    delivery_commit, authority_snapshot, valid_until, bundle_digest,
                    version, created_at, created_by_principal_id
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 1, 'APPROVED', ?, ?, 1, 0, ?, ?, ?,
                    ?, ?, ?, ?, ?, 0, 'OWNER', ?, ?, 0, ?, 0, ?, 0,
                    'SOURCE_CODE', 'TEAM_SERVICE_ACCOUNT', ?, 0, ?, 0, ?, ?, 1, ?, ?, 1,
                    ?, ?, 0, 'crewscope', 'main', ?, 1, ?, ?, ?, '{}'::JSONB, ?, ?, 0, ?, ?
                )
                """,
                fixture.bundleId(), organizationId.value(), teamId, workspaceId, projectId,
                workItemId, fixture.taskId(), fixture.executionId(), UUID.randomUUID(), HASH,
                UUID.randomUUID(), HASH, UUID.randomUUID(), HASH, UUID.randomUUID(), HASH,
                UUID.randomUUID(), HASH, assignmentId, principalId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), fixture.connectionId(), UUID.randomUUID(),
                HASH, UUID.randomUUID(), HASH, UUID.randomUUID(), HASH, UUID.randomUUID(),
                UUID.randomUUID(), HASH, BASE_COMMIT, DELIVERY_COMMIT,
                createdAt.plusSeconds(600).atOffset(ZoneOffset.UTC), fixture.bundleDigest(),
                createdAt.atOffset(ZoneOffset.UTC), principalId));
        return fixture;
    }

    private UUID seedConfirmation(
            ActionBundleFixture bundle, String status, Instant confirmedAt) {
        UUID confirmationId = UUID.randomUUID();
        inReplica(() -> jdbc.update(
                """
                INSERT INTO crewscope.action_confirmation (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmed_by_principal_id,
                    confirmed_at, valid_until, status, cancellation_reason, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """,
                confirmationId, organizationId.value(), teamId, workspaceId, projectId,
                bundle.bundleId(), bundle.bundleDigest(), principalId,
                confirmedAt.atOffset(ZoneOffset.UTC),
                confirmedAt.plusSeconds(300).atOffset(ZoneOffset.UTC), status,
                status.equals("CANCELLED") ? "CONFIRMATION_CANCELLED" : null,
                confirmedAt.atOffset(ZoneOffset.UTC), principalId,
                confirmedAt.atOffset(ZoneOffset.UTC), principalId));
        return confirmationId;
    }

    private TaskFixture seedFailedTask(Instant createdAt) {
        TaskFixture fixture = new TaskFixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.task (
                        id, organization_id, team_id, workspace_id, project_id, work_item_id,
                        source_type, source_work_item_version, responsibility_snapshot_id,
                        status, current_execution_id, version, objective, acceptance_criteria,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'FAILED', ?, 0,
                        'Recover failed task', '[]'::JSONB, ?, ?, ?, ?)
                    """,
                    fixture.taskId(), organizationId.value(), teamId, workspaceId, projectId,
                    workItemId, fixture.snapshotId(), fixture.failedExecutionId(),
                    createdAt.atOffset(ZoneOffset.UTC), principalId,
                    createdAt.plusSeconds(1).atOffset(ZoneOffset.UTC), principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.task_execution (
                        id, organization_id, team_id, workspace_id, project_id, task_id,
                        attempt, max_attempts, priority, not_before, status,
                        terminal_decided_by_principal_id, terminal_decided_at,
                        terminal_failure_class, terminal_failure_code, version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, 3, 50, ?, 'FAILED', ?, ?,
                        'INTERNAL', 'WORKER_FAILED', 1, ?, ?, ?, ?)
                    """,
                    fixture.failedExecutionId(), organizationId.value(), teamId, workspaceId,
                    projectId, fixture.taskId(), createdAt.atOffset(ZoneOffset.UTC), principalId,
                    createdAt.plusSeconds(1).atOffset(ZoneOffset.UTC),
                    createdAt.atOffset(ZoneOffset.UTC), principalId,
                    createdAt.plusSeconds(1).atOffset(ZoneOffset.UTC), principalId);
        });
        return fixture;
    }

    private void seedRetryExecution(TaskFixture task, Instant createdAt) {
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.task_execution (
                        id, organization_id, team_id, workspace_id, project_id, task_id,
                        attempt, max_attempts, parent_execution_id, priority, not_before,
                        status, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 2, 3, ?, 50, ?, 'READY', 0, ?, ?, ?, ?)
                    """,
                    task.retryExecutionId(), organizationId.value(), teamId, workspaceId,
                    projectId, task.taskId(), task.failedExecutionId(),
                    createdAt.atOffset(ZoneOffset.UTC), createdAt.atOffset(ZoneOffset.UTC),
                    principalId, createdAt.atOffset(ZoneOffset.UTC), principalId);
            jdbc.update(
                    """
                    UPDATE crewscope.task
                    SET status = 'ACTIVE', current_execution_id = ?, version = version + 1,
                        updated_at = ?, updated_by_principal_id = ?
                    WHERE id = ?
                    """,
                    task.retryExecutionId(), createdAt.atOffset(ZoneOffset.UTC),
                    principalId, task.taskId());
        });
    }

    private UUID seedActionDispatch(
            ActionBundleFixture bundle,
            UUID confirmationId,
            int sequence,
            String status,
            Instant createdAt) {
        UUID actionId = UUID.randomUUID();
        UUID dispatchId = UUID.randomUUID();
        String idempotencyKey = sequence == 1 ? "d".repeat(64) : "e".repeat(64);
        String branch = "refs/heads/crewscope/delivery-" + sequence;
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.planned_action (
                        id, action_bundle_id, sequence, action_kind, external_repository_id,
                        connection_id, branch_full_ref, delivery_head, expected_remote_head,
                        parameter_snapshot, risk, valid_until, action_digest
                    ) VALUES (?, ?, ?, 'PUSH_BRANCH', 'repository-1', ?, ?, ?, ?,
                        '{}'::JSONB, 'HIGH_RISK_WRITE', ?, ?)
                    """,
                    actionId, bundle.bundleId(), sequence, bundle.connectionId(), branch,
                    DELIVERY_COMMIT, BASE_COMMIT,
                    createdAt.plusSeconds(300).atOffset(ZoneOffset.UTC), HASH);
            jdbc.update(
                    """
                    INSERT INTO crewscope.action_dispatch (
                        id, organization_id, team_id, workspace_id, project_id,
                        action_bundle_id, bundle_digest, confirmation_id, action_id,
                        action_digest, sequence, idempotency_key, valid_until, status,
                        last_fencing_token, claim_attempts, reconciliation_attempts,
                        not_before, receipt_id, compensation_disposition, version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?,
                        'NOT_REQUIRED', 0, ?, ?, ?, ?)
                    """,
                    dispatchId, organizationId.value(), teamId, workspaceId, projectId,
                    bundle.bundleId(), bundle.bundleDigest(), confirmationId, actionId, HASH,
                    sequence, idempotencyKey,
                    createdAt.plusSeconds(300).atOffset(ZoneOffset.UTC), status,
                    createdAt.atOffset(ZoneOffset.UTC),
                    status.equals("SUCCEEDED") ? UUID.randomUUID() : null,
                    createdAt.atOffset(ZoneOffset.UTC), principalId,
                    createdAt.atOffset(ZoneOffset.UTC), principalId);
        });
        return actionId;
    }

    private void resolveDispatch(UUID actionId, Instant resolvedAt) {
        inReplica(() -> jdbc.update(
                """
                UPDATE crewscope.action_dispatch
                SET status = 'SUCCEEDED', receipt_id = ?, version = version + 1,
                    updated_at = ?, updated_by_principal_id = ?
                WHERE action_id = ?
                """,
                UUID.randomUUID(), resolvedAt.atOffset(ZoneOffset.UTC), principalId, actionId));
    }

    private void inReplica(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            work.run();
        });
    }

    private String actionBundlePayload(UUID bundleId) {
        return """
                {"actionBundleId":"%s","validUntil":"2026-08-26T03:00:00Z"}
                """.formatted(bundleId);
    }

    private String closeReason(String itemType, UUID sourceId) {
        return jdbc.queryForObject(
                """
                SELECT close_reason FROM crewscope.inbox_item
                WHERE item_type = ? AND source_id = ?
                """,
                String.class, itemType, sourceId);
    }

    private int openActionExceptionCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.inbox_item
                WHERE item_type = 'EXCEPTION' AND source_type = 'ACTION_DELIVERY'
                  AND source_status = 'OPEN'
                """,
                Integer.class);
    }

    private int actionExceptionCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.inbox_item
                WHERE item_type = 'EXCEPTION' AND source_type = 'ACTION_DELIVERY'
                """,
                Integer.class);
    }

    private void seedScope() {
        organizationId = OrganizationId.generate();
        teamId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        principalId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        workItemId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Inbox Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Inbox Team', 'ACTIVE')",
                teamId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Inbox Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId);
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Inbox Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?, ?, ?)
                """,
                memberId, organizationId.value(), teamId, principalId,
                BASE_TIME.atOffset(ZoneOffset.UTC), BASE_TIME.atOffset(ZoneOffset.UTC),
                BASE_TIME.atOffset(ZoneOffset.UTC));
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'INBOX', 'Inbox Project', ?, ?)
                """,
                projectId, organizationId.value(), teamId, workspaceId, principalId, principalId);
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'INBOX-1', 'TASK', 'Inbox item',
                          'IN_PROGRESS', 'HIGH', ?, ?)
                """,
                workItemId, organizationId.value(), teamId, workspaceId, projectId,
                principalId, principalId);
    }

    private UUID seedAssignment(String role, Instant acceptedAt) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                """,
                assignmentId, organizationId.value(), teamId, workspaceId, projectId,
                workItemId, role, principalId, memberId, principalId,
                acceptedAt.atOffset(ZoneOffset.UTC), acceptedAt.atOffset(ZoneOffset.UTC),
                acceptedAt.atOffset(ZoneOffset.UTC), principalId,
                acceptedAt.atOffset(ZoneOffset.UTC), principalId);
        return assignmentId;
    }

    private void release(UUID assignmentId, Instant occurredAt) {
        jdbc.update(
                """
                UPDATE crewscope.responsibility_assignment
                SET status = 'RELEASED', released_by_principal_id = ?, released_at = ?,
                    version = version + 1, updated_at = ?, updated_by_principal_id = ?
                WHERE id = ?
                """,
                principalId, occurredAt.atOffset(ZoneOffset.UTC),
                occurredAt.atOffset(ZoneOffset.UTC), principalId, assignmentId);
    }

    private UUID seedEvent(
            String eventType, UUID aggregateId, long aggregateVersion,
            Instant occurredAt, String payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    workspace_id, subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id, occurred_at, payload
                ) VALUES (?, ?, '1', ?, ?, ?, 'RESPONSIBILITY_ASSIGNMENT', ?, ?,
                          'USER', ?, ?, ?, CAST(? AS JSONB))
                """,
                eventId, eventType, organizationId.value(), teamId, workspaceId,
                aggregateId, aggregateVersion, principalId, UUID.randomUUID(),
                occurredAt.atOffset(ZoneOffset.UTC), payload);
        jdbc.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status,
                    retry_count, created_at, version, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
                """,
                UUID.randomUUID(), eventId, PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                organizationId + ":RESPONSIBILITY_ASSIGNMENT:" + aggregateId,
                occurredAt.atOffset(ZoneOffset.UTC), occurredAt.atOffset(ZoneOffset.UTC));
        return eventId;
    }

    private String responsibilityPayload(String role, Optional<UUID> replacedAssignmentId) {
        return replacedAssignmentId
                .map(id -> """
                        {"workItemId":"%s","role":"%s","actorPrincipalId":"%s",
                         "replacedAssignmentId":"%s"}
                        """.formatted(workItemId, role, principalId, id))
                .orElseGet(() -> """
                        {"workItemId":"%s","role":"%s","actorPrincipalId":"%s"}
                        """.formatted(workItemId, role, principalId));
    }

    private EventPublication publication(UUID eventId) {
        return new JdbcProjectionEventHistoryStore(jdbc, objectMapper)
                .read(organizationId, Optional.empty(), 100)
                .events().stream()
                .map(ProjectionHistoryEvent::publication)
                .filter(event -> event.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private Shadow startShadow() {
        ProjectionRebuildJobId jobId = ProjectionRebuildJobId.generate();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        rebuild_job_id, status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 2, 1, ?, 'BUILDING', 1, 0, ?, ?)
                    """,
                    organizationId.value(), InboxEventProjector.PROJECTION_NAME.value(),
                    jobId.value(), BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC),
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC));
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_rebuild_job (
                        id, organization_id, projection_name, definition_version,
                        generation, requested_by_principal_id, status, version,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, 1, 2, ?, 'BUILDING', 0, ?, ?)
                    """,
                    jobId.value(), organizationId.value(),
                    InboxEventProjector.PROJECTION_NAME.value(), principalId,
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC),
                    BASE_TIME.plusSeconds(20).atOffset(ZoneOffset.UTC));
        });
        return new Shadow(lease(new ProjectionGeneration(2)), jobId);
    }

    private ProjectionGenerationLease lease(ProjectionGeneration generation) {
        return new ProjectionGenerationLease(
                new ProjectionGenerationKey(
                        organizationId, InboxEventProjector.PROJECTION_NAME, generation),
                ProjectionFencingToken.INITIAL);
    }

    private int itemCount(ProjectionGeneration generation) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.inbox_item
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class, organizationId.value(),
                InboxEventProjector.PROJECTION_NAME.value(), generation.value());
    }

    private int countBy(String itemType, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.inbox_item WHERE item_type = ? AND source_status = ?",
                Integer.class, itemType, status);
    }

    private int receiptCount(ProjectionGeneration generation) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_consumer_receipt
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                Integer.class, organizationId.value(),
                InboxEventProjector.PROJECTION_NAME.value(), generation.value());
    }

    private record ReviewFixture(UUID requestId) {}

    private record ActionBundleFixture(
            UUID bundleId,
            UUID taskId,
            UUID executionId,
            UUID connectionId,
            String bundleDigest) {}

    private record TaskFixture(
            UUID taskId,
            UUID failedExecutionId,
            UUID retryExecutionId,
            UUID snapshotId) {}

    private record Shadow(
            ProjectionGenerationLease lease, ProjectionRebuildJobId jobId) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}

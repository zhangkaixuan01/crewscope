package io.crewscope.domain.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionReference;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestReference;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewSubjectReference;
import io.crewscope.domain.review.ReviewSubjectType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.action.ActionAuthorityJsonCodec;
import io.crewscope.infrastructure.persistence.action.JdbcActionDefinitionRepositoryAdapter;
import io.crewscope.infrastructure.persistence.action.JdbcActionExecutionRepositoryAdapter;
import io.crewscope.infrastructure.persistence.action.JdbcExternalResultRepositoryAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL proof for exact graph recovery, Fencing and atomic Receipt/terminal commit. */
class JdbcActionWorkerPersistenceM5I11IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcActionDefinitionRepositoryAdapter definitions;
    private JdbcActionExecutionRepositoryAdapter executions;
    private JdbcExternalResultRepositoryAdapter externalResults;

    @BeforeEach
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        definitions = new JdbcActionDefinitionRepositoryAdapter(
                jdbc, new ActionAuthorityJsonCodec(new ObjectMapper()));
        executions = new JdbcActionExecutionRepositoryAdapter(jdbc);
        externalResults = new JdbcExternalResultRepositoryAdapter(jdbc);
    }

    @Test
    void roundTripsClaimAndCommitsReceiptWithTerminalDispatchInOneTransaction() {
        Fixture fixture = new Fixture();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertPrincipal(fixture);
            definitions.insert(fixture.bundle);
            definitions.insert(fixture.confirmation);
            executions.insertAll(List.of(fixture.pushDispatch, fixture.pullRequestDispatch));
            jdbc.execute("SET LOCAL session_replication_role = origin");

            ActionBundle recoveredBundle = definitions.findById(
                            fixture.scope.organizationId(), fixture.bundle.id())
                    .orElseThrow();
            assertEquals(fixture.bundle.digest(), recoveredBundle.digest());
            assertEquals(fixture.bundle.actions().stream().map(PlannedAction::digest).toList(),
                    recoveredBundle.actions().stream().map(PlannedAction::digest).toList());

            ActionClaim claim = new ActionClaim(
                    fixture.pushDispatch.id(),
                    fixture.push.id(),
                    new ActionWorkerId("postgres-m5-i11"),
                    new ActionFencingToken(1),
                    ActionClaimMode.EXECUTE,
                    fixture.claimedAt,
                    fixture.claimedAt,
                    plus(fixture.claimedAt, 120));
            ActionDispatch claimed = ActionDispatch.reconstitute(
                    fixture.pushDispatch.id(),
                    fixture.pushDispatch.scope(),
                    fixture.pushDispatch.bundleId(),
                    fixture.pushDispatch.bundleDigest(),
                    fixture.pushDispatch.confirmationId(),
                    fixture.pushDispatch.actionId(),
                    fixture.pushDispatch.actionDigest(),
                    fixture.pushDispatch.sequence(),
                    fixture.pushDispatch.dependencies(),
                    fixture.pushDispatch.idempotencyKey(),
                    fixture.pushDispatch.validUntil(),
                    ActionDispatchStatus.RUNNING,
                    Optional.of(claim),
                    1,
                    1,
                    0,
                    fixture.pushDispatch.notBefore(),
                    Optional.empty(),
                    Optional.empty(),
                    CompensationDisposition.NOT_REQUIRED,
                    1,
                    fixture.pushDispatch.audit().modifiedBy(fixture.owner.id(), fixture.claimedAt));
            ActionDispatch committedClaim = executions.update(claimed);
            assertEquals(claim, committedClaim.claim().orElseThrow());

            UtcTimestamp receivedAt = plus(fixture.claimedAt, 1);
            ExternalResultIdentity branchIdentity = new ExternalResultIdentity(
                    fixture.connectionId,
                    ExternalObjectType.BRANCH,
                    "101:branch:" + fixture.branch.value(),
                    "101:branch:" + fixture.branch.value());
            ActionReceipt receipt = ActionReceipt.fromClaim(
                    ActionReceiptId.generate(),
                    committedClaim,
                    fixture.push,
                    committedClaim.claim().orElseThrow(),
                    ActionReceiptResult.SUCCEEDED,
                    ActionResultSource.WRITE_RESPONSE,
                    Optional.of(branchIdentity),
                    Optional.of(fixture.delivery.value()),
                    ActionEvidenceReference.hashed(
                            "GITHUB_PUSH_PUSHED", fixture.delivery.value()),
                    receivedAt);
            ActionReceipt committedReceipt = executions.insertIfAbsent(receipt).receipt();
            ActionDispatch completed = committedClaim.completeClaimed(
                    committedClaim.version(),
                    committedClaim.claim().orElseThrow(),
                    committedReceipt,
                    receivedAt);
            executions.update(completed);
        });

        ActionReceipt recoveredReceipt = executions.findReceiptByAction(
                        fixture.scope.organizationId(), fixture.push.id())
                .orElseThrow();
        assertEquals(fixture.delivery.value(), recoveredReceipt.targetVersion().orElseThrow());
        assertEquals("postgres-m5-i11", recoveredReceipt.claim().orElseThrow().workerId().value());
        assertEquals(1, recoveredReceipt.claim().orElseThrow().fencingToken().value());
        ActionDispatch recoveredDispatch = executions.findByAction(
                        fixture.scope.organizationId(), fixture.push.id())
                .orElseThrow();
        assertEquals(ActionDispatchStatus.SUCCEEDED, recoveredDispatch.status());
        assertEquals(recoveredReceipt.id(), recoveredDispatch.receipt().orElseThrow().id());
        List<ActionDispatch> released = executions.lockClaimable(
                fixture.scope.organizationId(), plus(fixture.claimedAt, 2), 10);
        assertEquals(1, released.size());
        assertEquals(fixture.pullRequest.id(), released.get(0).actionId());
    }

    @Test
    void concurrentWorkersCannotLockTheSameReadyDispatch() throws Exception {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        CountDownLatch firstWorkerLocked = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> transactions.execute(status -> {
                List<ActionDispatch> locked = executions.lockClaimable(
                        fixture.scope.organizationId(), fixture.claimedAt, 1);
                firstWorkerLocked.countDown();
                await(releaseFirstWorker);
                return locked.size();
            }));
            assertTrue(firstWorkerLocked.await(10, TimeUnit.SECONDS));
            Future<Integer> second = workers.submit(() -> transactions.execute(status ->
                    executions.lockClaimable(
                            fixture.scope.organizationId(), fixture.claimedAt, 1).size()));
            assertEquals(0, second.get(10, TimeUnit.SECONDS));
            releaseFirstWorker.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS));
        } finally {
            releaseFirstWorker.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void organizationPollingSkipsReadyDispatchesWhoseDependenciesAreStillBlocked() {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        ActionDispatch claim = transactions.execute(status ->
                executions.update(claimed(fixture.pushDispatch, fixture, 1)));

        assertTrue(executions.findClaimableOrganizations(fixture.claimedAt, 10).isEmpty());

        transactions.executeWithoutResult(status -> executions.insertIfAbsent(
                successfulReceipt(fixture, claim, plus(fixture.claimedAt, 1))));
        assertEquals(
                List.of(fixture.scope.organizationId()),
                executions.findClaimableOrganizations(plus(fixture.claimedAt, 2), 10));
    }

    @Test
    void oldFencingTokenCannotRecordAReceiptAfterAReplacementClaim() {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        ActionDispatch firstClaim = transactions.execute(status ->
                executions.update(claimed(fixture.pushDispatch, fixture, 1)));
        ActionDispatch retried = transactions.execute(status -> executions.update(
                readyAfterClaim(firstClaim, fixture, plus(fixture.claimedAt, 1))));
        transactions.execute(status -> executions.update(claimed(retried, fixture, 2)));

        ActionReceipt staleReceipt = successfulReceipt(
                fixture, firstClaim, plus(fixture.claimedAt, 2));
        assertThrows(DataIntegrityViolationException.class, () ->
                transactions.executeWithoutResult(status -> executions.insertIfAbsent(staleReceipt)));
        assertTrue(executions.findReceiptByAction(
                        fixture.scope.organizationId(), fixture.push.id())
                .isEmpty());
    }

    @Test
    void receiptRollsBackWhenTheTerminalDispatchUpdateLosesItsVersion() {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        ActionDispatch claim = transactions.execute(status ->
                executions.update(claimed(fixture.pushDispatch, fixture, 1)));
        UtcTimestamp receivedAt = plus(fixture.claimedAt, 2);
        ActionReceipt receipt = successfulReceipt(fixture, claim, receivedAt);

        assertThrows(OptimisticLockConflictException.class, () ->
                transactions.executeWithoutResult(status -> {
                    executions.insertIfAbsent(receipt);
                    ActionDispatch heartbeat = claim.heartbeat(
                            claim.version(),
                            claim.claim().orElseThrow(),
                            plus(fixture.claimedAt, 1),
                            plus(fixture.claimedAt, 121));
                    executions.update(heartbeat);
                    ActionDispatch staleCompletion = claim.completeClaimed(
                            claim.version(), claim.claim().orElseThrow(), receipt, receivedAt);
                    executions.update(staleCompletion);
                }));

        assertTrue(executions.findReceiptByAction(
                        fixture.scope.organizationId(), fixture.push.id())
                .isEmpty());
        ActionDispatch recovered = executions.findByAction(
                        fixture.scope.organizationId(), fixture.push.id())
                .orElseThrow();
        assertEquals(ActionDispatchStatus.RUNNING, recovered.status());
        assertEquals(1, recovered.version());
    }

    @Test
    void duplicateLogicalReceiptKeepsTheFirstAppendOnlyRecord() {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        ActionDispatch claim = transactions.execute(status ->
                executions.update(claimed(fixture.pushDispatch, fixture, 1)));
        ActionReceipt first = successfulReceipt(fixture, claim, plus(fixture.claimedAt, 1));
        ActionReceipt duplicate = successfulReceipt(fixture, claim, plus(fixture.claimedAt, 2));

        transactions.executeWithoutResult(status -> {
            assertTrue(executions.insertIfAbsent(first).inserted());
            var recovered = executions.insertIfAbsent(duplicate);
            assertFalse(recovered.inserted());
            assertEquals(first.id(), recovered.receipt().id());
            assertEquals(first.receivedAt(), recovered.receipt().receivedAt());
        });
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.action_receipt WHERE action_id = ?",
                Integer.class,
                fixture.push.id().value()));
    }

    @Test
    void expiredLeaseIsFencedIntoReconciliationAndEscalatesToTheManualQueue() {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        ActionDispatch running = transactions.execute(status ->
                executions.update(claimed(fixture.pushDispatch, fixture, 1)));

        assertTrue(executions.findReconciliationOrganizations(
                        plus(fixture.claimedAt, 119), 10)
                .isEmpty());
        UtcTimestamp takeoverAt = plus(fixture.claimedAt, 121);
        assertEquals(
                List.of(fixture.scope.organizationId()),
                executions.findReconciliationOrganizations(takeoverAt, 10));

        ActionDispatch manual = transactions.execute(status -> {
            ActionDispatch candidate = executions.lockReconciliationCandidates(
                            fixture.scope.organizationId(), takeoverAt, 10)
                    .get(0);
            ActionDispatch takeover = candidate.claimForReconciliation(
                    candidate.version(),
                    fixture.bundle,
                    fixture.confirmation,
                    List.of(),
                    new ActionWorkerId("postgres-m5-i12"),
                    takeoverAt,
                    plus(takeoverAt, 120));
            ActionDispatch committed = executions.update(takeover);
            ActionDispatch escalated = committed.recordInconclusiveReconciliation(
                    committed.version(),
                    committed.claim().orElseThrow(),
                    1,
                    plus(takeoverAt, 1),
                    plus(takeoverAt, 1));
            return executions.update(escalated);
        });

        assertEquals(ActionDispatchStatus.MANUAL_REVIEW, manual.status());
        assertEquals(
                List.of(manual.id()),
                executions.findManualReview(fixture.scope.organizationId(), 10)
                        .stream()
                        .map(ActionDispatch::id)
                        .toList());
        assertEquals(1, executions.reconciliationHealth().manualReview());
        assertEquals(1, executions.reconciliationHealth().unresolved());
        assertEquals(running.id(), manual.id());
    }

    @Test
    void externalObservationsRoundTripAndMergeThroughOneOptimisticProjection() {
        Fixture fixture = new Fixture();
        persistReady(fixture);
        ExternalResultIdentity identity = new ExternalResultIdentity(
                fixture.connectionId,
                ExternalObjectType.BRANCH,
                "101:branch:" + fixture.branch.value(),
                "101:branch:" + fixture.branch.value());
        ExternalObservation first = new ExternalObservation(
                ExternalObservationKey.derive(
                        fixture.connectionId, ExternalResultSource.ACTIVE_QUERY, "m5-i12-query-1"),
                fixture.push.id(),
                fixture.push.digest(),
                identity,
                ExternalObjectStatus.PRESENT,
                Optional.empty(),
                Optional.of(plus(fixture.claimedAt, 1)),
                ExternalResultSource.ACTIVE_QUERY,
                ActionEvidenceReference.hashed("GITHUB_BRANCH_ACTIVE_QUERY", "present"),
                plus(fixture.claimedAt, 1));

        ExternalResult committed = transactions.execute(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            assertTrue(externalResults.appendIfAbsent(fixture.scope.organizationId(), first));
            assertFalse(externalResults.appendIfAbsent(fixture.scope.organizationId(), first));
            jdbc.execute("SET LOCAL session_replication_role = origin");
            ExternalResult created = ExternalResult.observeFirstFromTrustedSource(
                    ExternalResultId.generate(),
                    fixture.pushDispatch,
                    fixture.push,
                    first,
                    fixture.owner.id());
            ExternalResult inserted = externalResults.insert(created);
            return inserted;
        });
        assertEquals(first.observationKey(), committed.lastObservationKey());

        ExternalObservation second = new ExternalObservation(
                ExternalObservationKey.derive(
                        fixture.connectionId, ExternalResultSource.ACTIVE_QUERY, "m5-i12-query-2"),
                fixture.push.id(),
                fixture.push.digest(),
                identity,
                ExternalObjectStatus.MISSING,
                Optional.empty(),
                Optional.of(plus(fixture.claimedAt, 2)),
                ExternalResultSource.ACTIVE_QUERY,
                ActionEvidenceReference.hashed("GITHUB_BRANCH_ACTIVE_QUERY", "missing"),
                plus(fixture.claimedAt, 2));
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            assertTrue(externalResults.appendIfAbsent(fixture.scope.organizationId(), second));
            jdbc.execute("SET LOCAL session_replication_role = origin");
            ExternalResult changed = committed.mergeFromTrustedSource(
                            committed.version(), second, Optional.empty(), fixture.owner.id())
                    .result();
            externalResults.update(changed);
        });

        ExternalResult recovered = externalResults.findByAction(
                        fixture.scope.organizationId(), fixture.push.id())
                .orElseThrow();
        assertEquals(ExternalObjectStatus.MISSING, recovered.status());
        assertEquals(1, recovered.version());
        assertEquals(
                List.of(first.observationKey(), second.observationKey()),
                externalResults.findObservationsByAction(
                                fixture.scope.organizationId(), fixture.push.id())
                        .stream()
                        .map(ExternalObservation::observationKey)
                        .toList());
    }

    private void persistReady(Fixture fixture) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            insertPrincipal(fixture);
            definitions.insert(fixture.bundle);
            definitions.insert(fixture.confirmation);
            executions.insertAll(List.of(fixture.pushDispatch, fixture.pullRequestDispatch));
            jdbc.execute("SET LOCAL session_replication_role = origin");
        });
    }

    private void insertPrincipal(Fixture fixture) {
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, display_name,
                    visibility, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'USER', ?, 'TEAM', 'ACTIVE', 0, ?, ?)
                """,
                fixture.owner.id().value(),
                fixture.scope.organizationId().value(),
                fixture.scope.teamId().value(),
                fixture.owner.displayName(),
                fixture.createdAt.toOffsetDateTime(),
                fixture.createdAt.toOffsetDateTime());
    }

    private static ActionDispatch claimed(
            ActionDispatch source, Fixture fixture, long fencingToken) {
        UtcTimestamp acquiredAt = plus(fixture.claimedAt, fencingToken - 1);
        ActionClaim claim = new ActionClaim(
                source.id(),
                source.actionId(),
                new ActionWorkerId("postgres-m5-i11-" + fencingToken),
                new ActionFencingToken(fencingToken),
                ActionClaimMode.EXECUTE,
                acquiredAt,
                acquiredAt,
                plus(acquiredAt, 120));
        return ActionDispatch.reconstitute(
                source.id(), source.scope(), source.bundleId(), source.bundleDigest(),
                source.confirmationId(), source.actionId(), source.actionDigest(), source.sequence(),
                source.dependencies(), source.idempotencyKey(), source.validUntil(),
                ActionDispatchStatus.RUNNING, Optional.of(claim), fencingToken,
                source.claimAttempts() + 1, source.reconciliationAttempts(), source.notBefore(),
                Optional.empty(), Optional.empty(), CompensationDisposition.NOT_REQUIRED,
                source.version() + 1,
                source.audit().modifiedBy(fixture.owner.id(), acquiredAt));
    }

    private static ActionDispatch readyAfterClaim(
            ActionDispatch source, Fixture fixture, UtcTimestamp occurredAt) {
        return ActionDispatch.reconstitute(
                source.id(), source.scope(), source.bundleId(), source.bundleDigest(),
                source.confirmationId(), source.actionId(), source.actionDigest(), source.sequence(),
                source.dependencies(), source.idempotencyKey(), source.validUntil(),
                ActionDispatchStatus.READY, Optional.empty(), source.lastFencingToken(),
                source.claimAttempts(), source.reconciliationAttempts(), occurredAt,
                Optional.empty(), Optional.empty(), CompensationDisposition.NOT_REQUIRED,
                source.version() + 1,
                source.audit().modifiedBy(fixture.owner.id(), occurredAt));
    }

    private static ActionReceipt successfulReceipt(
            Fixture fixture, ActionDispatch claim, UtcTimestamp receivedAt) {
        ExternalResultIdentity identity = new ExternalResultIdentity(
                fixture.connectionId,
                ExternalObjectType.BRANCH,
                "101:branch:" + fixture.branch.value(),
                "101:branch:" + fixture.branch.value());
        return ActionReceipt.fromClaim(
                ActionReceiptId.generate(),
                claim,
                fixture.push,
                claim.claim().orElseThrow(),
                ActionReceiptResult.SUCCEEDED,
                ActionResultSource.WRITE_RESPONSE,
                Optional.of(identity),
                Optional.of(fixture.delivery.value()),
                ActionEvidenceReference.hashed("GITHUB_PUSH_PUSHED", fixture.delivery.value()),
                receivedAt);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent Action claim proof");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent Action claim proof was interrupted", interrupted);
        }
    }

    private static final class Fixture {

        private final UtcTimestamp createdAt = UtcTimestamp.parse("2026-08-23T10:00:00Z");
        private final UtcTimestamp confirmedAt = plus(createdAt, 1);
        private final UtcTimestamp dispatchAt = plus(createdAt, 2);
        private final UtcTimestamp claimedAt = plus(createdAt, 3);
        private final UtcTimestamp validUntil = plus(createdAt, 600);
        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        private final Principal owner = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(scope.organizationId(), scope.teamId()),
                PrincipalType.USER,
                Optional.empty(),
                "M5 I11 Owner",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                createdAt);
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ConnectionId connectionId = ConnectionId.generate();
        private final RepositoryCommitId baseline = new RepositoryCommitId("a".repeat(40));
        private final RepositoryCommitId delivery = new RepositoryCommitId("b".repeat(40));
        private final RepositoryBranchReference branch =
                new RepositoryBranchReference("refs/heads/crewscope/m5-i11");
        private final ActionAuthoritySnapshot authority = authority();
        private final PlannedAction push = PlannedAction.plan(
                PlannedActionId.generate(),
                1,
                new PushBranchActionParameters(
                        new ExternalRepositoryId("101"),
                        branch,
                        delivery,
                        Optional.of(baseline),
                        connectionId),
                List.of(),
                authority,
                ActionRiskLevel.HIGH_RISK_WRITE,
                validUntil);
        private final PlannedAction pullRequest = PlannedAction.plan(
                PlannedActionId.generate(),
                2,
                new CreateDraftPullRequestActionParameters(
                        new ExternalRepositoryId("101"),
                        branch.shortName(),
                        authority.targetPrecondition().defaultBranch(),
                        delivery,
                        "M5 I11 delivery",
                        "Reviewed delivery",
                        true,
                        connectionId),
                List.of(new ActionDependency(push.id())),
                authority,
                ActionRiskLevel.LOW_RISK_WRITE,
                validUntil);
        private final ActionBundle bundle = ActionBundle.planGraph(
                ActionBundleId.generate(),
                authority,
                List.of(push, pullRequest),
                validUntil,
                owner,
                createdAt);
        private final Confirmation confirmation = Confirmation.reconstitute(
                ConfirmationId.generate(),
                scope,
                bundle.id(),
                bundle.digest(),
                bundle.actions().stream().map(ConfirmedActionReference::from).toList(),
                owner.id(),
                confirmedAt,
                validUntil,
                ConfirmationStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(owner.id(), confirmedAt));
        private final ActionDispatch pushDispatch = ActionDispatch.schedule(
                ActionDispatchId.generate(), bundle, push, confirmation, owner, dispatchAt);
        private final ActionDispatch pullRequestDispatch = ActionDispatch.schedule(
                ActionDispatchId.generate(), bundle, pullRequest, confirmation, owner, dispatchAt);

        private ActionAuthoritySnapshot authority() {
            ReviewSubjectReference subject = new ReviewSubjectReference(
                    ReviewSubjectId.generate(),
                    ReviewSubjectType.CODE_CHANGE,
                    TaskFactHash.sha256("subject"));
            ContextPackageReference context = new ContextPackageReference(
                    ContextPackageId.generate(), 1, TaskFactHash.sha256("context"));
            ReviewRequestReference request = new ReviewRequestReference(
                    scope,
                    taskId,
                    executionId,
                    1,
                    ReviewRequestId.generate(),
                    1,
                    2,
                    subject,
                    context,
                    TaskFactHash.sha256("request"));
            CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                    CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
            String patch = "+class M5I11 {}\n";
            ReviewDiffReference diff = new ReviewDiffReference(
                    scope,
                    taskId,
                    executionId,
                    1,
                    new DiffArtifactReference(
                            DiffArtifactId.generate(), TaskFactHash.sha256("diff")),
                    target,
                    baseline,
                    delivery,
                    DiffGeneration.first(),
                    RuntimeContentHash.sha256("manifest"),
                    new PatchArtifactReference(
                            ArtifactId.generate(),
                            patch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                            RuntimeContentHash.sha256(patch)),
                    List.of(new DiffPath("src/M5I11.java")));
            return new ActionAuthoritySnapshot(
                    scope,
                    WorkItemId.generate(),
                    taskId,
                    executionId,
                    1,
                    new ReviewDecisionReference(
                            ReviewDecisionId.generate(),
                            1,
                            request,
                            ReviewDecisionType.APPROVED,
                            TaskFactHash.sha256("decision")),
                    diff,
                    new ResponsibilityReference(
                            ResponsibilityAssignmentId.generate(),
                            0,
                            ResponsibilityRole.OWNER,
                            owner.id()),
                    new ProviderAuthorizationReference(
                            ProviderBindingId.generate(),
                            0,
                            ProviderDefinitionId.generate(),
                            1,
                            ProviderImplementationId.generate(),
                            1,
                            ProviderType.SOURCE_CODE,
                            ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT,
                            connectionId,
                            0,
                            ConnectionGrantId.generate(),
                            0,
                            TaskFactHash.sha256("access")),
                    new ActionPolicyReference(
                            PolicySnapshotId.generate(), 1, TaskFactHash.sha256("policy")),
                    new SafetyEnforcementOverlayReference(
                            SafetyEnforcementOverlayId.generate(),
                            1,
                            TaskFactHash.sha256("safety")),
                    new ActionTargetPrecondition(
                            RepositoryBindingId.generate(),
                            0,
                            new RepositoryKey("crewscope-java"),
                            new RepositoryBranchName("main"),
                            target,
                            baseline,
                            delivery));
        }
    }

    private static UtcTimestamp plus(UtcTimestamp value, long seconds) {
        return UtcTimestamp.from(value.value().plusSeconds(seconds));
    }
}

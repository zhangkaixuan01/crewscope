package io.crewscope.infrastructure.persistence.action;

import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReceiptInsertResult;
import io.crewscope.application.action.ActionReceiptRepository;
import io.crewscope.application.action.ActionReconciliationHealth;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionCancellationReason;
import io.crewscope.domain.action.ActionClaim;
import io.crewscope.domain.action.ActionClaimMode;
import io.crewscope.domain.action.ActionDependency;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ActionFencingToken;
import io.crewscope.domain.action.ActionIdempotencyKey;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptId;
import io.crewscope.domain.action.ActionReceiptReference;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ActionResultSource;
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.action.CompensationDisposition;
import io.crewscope.domain.action.ConfirmationId;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ManualResolutionReason;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Fenced PostgreSQL queue and append-only logical Receipt store for Action execution. */
@Repository
public class JdbcActionExecutionRepositoryAdapter
        implements ActionDispatchRepository, ActionReceiptRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcActionExecutionRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional
    public List<ActionDispatch> insertAll(List<ActionDispatch> dispatches) {
        List<ActionDispatch> values = List.copyOf(Objects.requireNonNull(dispatches, "dispatches"));
        for (ActionDispatch value : values) {
            insertDispatch(value);
        }
        return values.stream()
                .map(value -> findById(value.scope().organizationId(), value.id()).orElseThrow())
                .toList();
    }

    private void insertDispatch(ActionDispatch value) {
        MapSqlParameterSource p = dispatchParameters(value);
        named.update(
                """
                INSERT INTO crewscope.action_dispatch (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmation_id, action_id,
                    action_digest, sequence, idempotency_key, valid_until, status,
                    claim_worker_id, claim_fencing_token, claim_mode, claim_acquired_at,
                    claim_last_heartbeat_at, claim_lease_until, last_fencing_token,
                    claim_attempts, reconciliation_attempts, not_before, receipt_id,
                    cancellation_reason, compensation_disposition, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :bundleId, :bundleDigest, :confirmationId, :actionId,
                    :actionDigest, :sequence, :idempotencyKey, :validUntil, :status,
                    :claimWorker, :claimFencing, :claimMode, :claimAcquired,
                    :claimHeartbeat, :claimLease, :lastFencing, :claimAttempts,
                    :reconciliationAttempts, :notBefore, :receiptId, :cancellation,
                    :compensation, :version, :createdAt, :createdBy, :updatedAt, :updatedBy
                )
                """,
                p);
        value.dependencies().forEach(dependency -> jdbc.update(
                """
                INSERT INTO crewscope.action_dispatch_dependency (
                    action_dispatch_id, predecessor_action_id
                ) VALUES (?, ?)
                """,
                value.id().value(), dependency.predecessorActionId().value()));
    }

    @Override
    @Transactional
    public ActionDispatch update(ActionDispatch dispatch) {
        ActionDispatch value = Objects.requireNonNull(dispatch, "dispatch");
        MapSqlParameterSource p = dispatchParameters(value)
                .addValue("expectedVersion", value.version() - 1);
        int changed = named.update(
                """
                UPDATE crewscope.action_dispatch SET
                    status = :status,
                    claim_worker_id = :claimWorker,
                    claim_fencing_token = :claimFencing,
                    claim_mode = :claimMode,
                    claim_acquired_at = :claimAcquired,
                    claim_last_heartbeat_at = :claimHeartbeat,
                    claim_lease_until = :claimLease,
                    last_fencing_token = :lastFencing,
                    claim_attempts = :claimAttempts,
                    reconciliation_attempts = :reconciliationAttempts,
                    not_before = :notBefore,
                    receipt_id = :receiptId,
                    cancellation_reason = :cancellation,
                    compensation_disposition = :compensation,
                    version = :version,
                    updated_at = :updatedAt,
                    updated_by_principal_id = :updatedBy
                WHERE organization_id = :organizationId AND id = :id
                    AND version = :expectedVersion
                    AND last_fencing_token <= :lastFencing
                """,
                p);
        if (changed != 1) {
            throw new OptimisticLockConflictException(
                    "ActionDispatch", value.id(), value.version() - 1, value.version());
        }
        return findById(value.scope().organizationId(), value.id()).orElseThrow();
    }

    @Override
    public Optional<ActionDispatch> findById(
            OrganizationId organizationId, ActionDispatchId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.action_dispatch WHERE organization_id = ? AND id = ?",
                this::dispatch,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ActionDispatch> findByAction(
            OrganizationId organizationId, PlannedActionId actionId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.action_dispatch
                WHERE organization_id = ? AND action_id = ?
                """,
                this::dispatch,
                organizationId.value(), actionId.value()));
    }

    @Override
    public List<ActionDispatch> findByBundle(
            OrganizationId organizationId, ActionBundleId bundleId) {
        return jdbc.query(
                """
                SELECT * FROM crewscope.action_dispatch
                WHERE organization_id = ? AND action_bundle_id = ?
                ORDER BY sequence, id
                """,
                this::dispatch,
                organizationId.value(), bundleId.value());
    }

    @Override
    public List<OrganizationId> findClaimableOrganizations(
            UtcTimestamp authoritativeNow, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Action organization limit must be between 1 and 100");
        }
        return jdbc.query(
                """
                SELECT DISTINCT dispatch.organization_id
                FROM crewscope.action_dispatch dispatch
                WHERE dispatch.not_before <= ?
                  AND dispatch.status = 'READY'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM crewscope.action_dispatch_dependency dependency
                    LEFT JOIN crewscope.action_receipt receipt
                      ON receipt.organization_id = dispatch.organization_id
                     AND receipt.action_id = dependency.predecessor_action_id
                     AND receipt.result IN ('SUCCEEDED', 'MANUALLY_SUCCEEDED')
                    WHERE dependency.action_dispatch_id = dispatch.id
                      AND receipt.id IS NULL
                  )
                ORDER BY dispatch.organization_id
                LIMIT ?
                """,
                (row, ignored) -> new OrganizationId(uuid(row, "organization_id")),
                time(authoritativeNow), limit);
    }

    @Override
    @Transactional
    public List<ActionDispatch> lockClaimable(
            OrganizationId organizationId, UtcTimestamp authoritativeNow, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Action claim limit must be between 1 and 100");
        }
        return jdbc.query(
                """
                SELECT dispatch.*
                FROM crewscope.action_dispatch dispatch
                WHERE dispatch.organization_id = ?
                  AND dispatch.not_before <= ?
                  AND dispatch.status = 'READY'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM crewscope.action_dispatch_dependency dependency
                    LEFT JOIN crewscope.action_receipt receipt
                      ON receipt.organization_id = dispatch.organization_id
                     AND receipt.action_id = dependency.predecessor_action_id
                     AND receipt.result IN ('SUCCEEDED', 'MANUALLY_SUCCEEDED')
                    WHERE dependency.action_dispatch_id = dispatch.id
                      AND receipt.id IS NULL
                  )
                ORDER BY dispatch.not_before, dispatch.created_at, dispatch.id
                FOR UPDATE OF dispatch SKIP LOCKED
                LIMIT ?
                """,
                this::dispatch,
                organizationId.value(), time(authoritativeNow), limit);
    }

    @Override
    public List<OrganizationId> findReconciliationOrganizations(
            UtcTimestamp authoritativeNow, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Action reconciliation organization limit must be between 1 and 100");
        }
        return jdbc.query(
                """
                SELECT DISTINCT dispatch.organization_id
                FROM crewscope.action_dispatch dispatch
                WHERE (
                    (dispatch.status = 'UNKNOWN' AND dispatch.not_before <= ?)
                    OR (dispatch.status IN ('RUNNING', 'RECONCILING')
                        AND dispatch.claim_lease_until <= ?)
                )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM crewscope.action_dispatch_dependency dependency
                    LEFT JOIN crewscope.action_receipt receipt
                      ON receipt.organization_id = dispatch.organization_id
                     AND receipt.action_id = dependency.predecessor_action_id
                     AND receipt.result IN ('SUCCEEDED', 'MANUALLY_SUCCEEDED')
                    WHERE dependency.action_dispatch_id = dispatch.id
                      AND receipt.id IS NULL
                  )
                ORDER BY dispatch.organization_id
                LIMIT ?
                """,
                (row, ignored) -> new OrganizationId(uuid(row, "organization_id")),
                time(authoritativeNow), time(authoritativeNow), limit);
    }

    @Override
    @Transactional
    public List<ActionDispatch> lockReconciliationCandidates(
            OrganizationId organizationId, UtcTimestamp authoritativeNow, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Action reconciliation limit must be between 1 and 100");
        }
        return jdbc.query(
                """
                SELECT dispatch.*
                FROM crewscope.action_dispatch dispatch
                WHERE dispatch.organization_id = ?
                  AND (
                    (dispatch.status = 'UNKNOWN' AND dispatch.not_before <= ?)
                    OR (dispatch.status IN ('RUNNING', 'RECONCILING')
                        AND dispatch.claim_lease_until <= ?)
                  )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM crewscope.action_dispatch_dependency dependency
                    LEFT JOIN crewscope.action_receipt receipt
                      ON receipt.organization_id = dispatch.organization_id
                     AND receipt.action_id = dependency.predecessor_action_id
                     AND receipt.result IN ('SUCCEEDED', 'MANUALLY_SUCCEEDED')
                    WHERE dependency.action_dispatch_id = dispatch.id
                      AND receipt.id IS NULL
                  )
                ORDER BY COALESCE(dispatch.claim_lease_until, dispatch.not_before),
                         dispatch.created_at, dispatch.id
                FOR UPDATE OF dispatch SKIP LOCKED
                LIMIT ?
                """,
                this::dispatch,
                organizationId.value(), time(authoritativeNow), time(authoritativeNow), limit);
    }

    @Override
    public List<ActionDispatch> findManualReview(
            OrganizationId organizationId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Action manual queue limit must be between 1 and 100");
        }
        return jdbc.query(
                """
                SELECT *
                FROM crewscope.action_dispatch
                WHERE organization_id = ? AND status = 'MANUAL_REVIEW'
                ORDER BY updated_at, id
                LIMIT ?
                """,
                this::dispatch,
                organizationId.value(), limit);
    }

    @Override
    public ActionReconciliationHealth reconciliationHealth() {
        return jdbc.queryForObject(
                """
                SELECT
                    COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,
                    COUNT(*) FILTER (WHERE status = 'UNKNOWN') AS unknown_count,
                    COUNT(*) FILTER (WHERE status = 'RECONCILING') AS reconciling,
                    COUNT(*) FILTER (WHERE status = 'MANUAL_REVIEW') AS manual_review,
                    MIN(updated_at) FILTER (WHERE status IN (
                        'RUNNING', 'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'
                    )) AS oldest_unresolved_at
                FROM crewscope.action_dispatch
                """,
                (row, ignored) -> new ActionReconciliationHealth(
                        row.getLong("running"),
                        row.getLong("unknown_count"),
                        row.getLong("reconciling"),
                        row.getLong("manual_review"),
                        Optional.ofNullable(row.getObject(
                                        "oldest_unresolved_at", OffsetDateTime.class))
                                .map(UtcTimestamp::from)));
    }

    @Override
    @Transactional
    public ActionReceiptInsertResult insertIfAbsent(ActionReceipt receipt) {
        ActionReceipt value = Objects.requireNonNull(receipt, "receipt");
        ActionDispatch dispatch = findByAction(value.scope().organizationId(), value.actionId())
                .orElseThrow(() -> new IllegalStateException("Action Dispatch is unavailable"));
        int inserted = insertReceipt(
                value, dispatch.id(), dispatch.audit().updatedBy().orElseThrow());
        if (inserted == 1) {
            return new ActionReceiptInsertResult(true, value);
        }
        ActionReceipt existing = findReceiptByAction(
                        value.scope().organizationId(), value.actionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Action Receipt uniqueness is owned by another external result"));
        if (!sameLogicalReceipt(existing, value)) {
            throw new IllegalStateException(
                    "Action Receipt already exists with a different terminal result");
        }
        return new ActionReceiptInsertResult(false, existing);
    }

    private int insertReceipt(
            ActionReceipt value, ActionDispatchId dispatchId, PrincipalId creator) {
        Optional<ActionClaim> claim = value.claim();
        Optional<ExternalResultIdentity> external = value.externalIdentity();
        MapSqlParameterSource p = scope(new MapSqlParameterSource(), value.scope())
                .addValue("id", value.id().value())
                .addValue("bundleId", value.bundleId().value())
                .addValue("bundleDigest", value.bundleDigest().toString())
                .addValue("dispatchId", dispatchId.value())
                .addValue("actionId", value.actionId().value())
                .addValue("actionDigest", value.actionDigest().toString())
                .addValue("idempotencyKey", value.idempotencyKey().toString())
                .addValue("result", value.result().name())
                .addValue("source", value.source().name())
                .addValue("claimWorker", claim.map(item -> item.workerId().value()).orElse(null))
                .addValue("claimFencing", claim.map(item -> item.fencingToken().value()).orElse(null))
                .addValue("claimMode", claim.map(item -> item.mode().name()).orElse(null))
                .addValue("claimAcquired", claim.map(item -> time(item.acquiredAt())).orElse(null))
                .addValue("claimHeartbeat", claim.map(item -> time(item.lastHeartbeatAt())).orElse(null))
                .addValue("claimLease", claim.map(item -> time(item.leaseUntil())).orElse(null))
                .addValue("connectionId", external.map(item -> item.connectionId().value()).orElse(null))
                .addValue("objectType", external.map(item -> item.objectType().name()).orElse(null))
                .addValue("externalId", external.map(ExternalResultIdentity::externalId).orElse(null))
                .addValue("businessKey", external.map(ExternalResultIdentity::businessKey).orElse(null))
                .addValue("targetVersion", value.targetVersion().orElse(null))
                .addValue("evidenceCode", value.evidence().code())
                .addValue("evidenceHash", value.evidence().evidenceHash().value())
                .addValue("evidenceArtifact", value.evidence().artifactId()
                        .map(ArtifactId::value).orElse(null))
                .addValue("resolvedBy", value.resolvedByPrincipalId()
                        .map(PrincipalId::value).orElse(null))
                .addValue("manualReason", value.manualReason().map(Enum::name).orElse(null))
                .addValue("receivedAt", time(value.receivedAt()))
                .addValue("createdBy", creator.value());
        return named.update(
                """
                INSERT INTO crewscope.action_receipt (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, action_dispatch_id, action_id,
                    action_digest, idempotency_key, result, source,
                    claim_worker_id, claim_fencing_token, claim_mode,
                    claim_acquired_at, claim_last_heartbeat_at, claim_lease_until,
                    connection_id, external_object_type, external_id,
                    external_business_key, target_version, evidence_code, evidence_hash,
                    evidence_artifact_id, resolved_by_principal_id, manual_reason,
                    received_at, created_at, created_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :bundleId, :bundleDigest, :dispatchId, :actionId,
                    :actionDigest, :idempotencyKey, :result, :source,
                    :claimWorker, :claimFencing, :claimMode,
                    :claimAcquired, :claimHeartbeat, :claimLease,
                    :connectionId, :objectType, :externalId, :businessKey,
                    :targetVersion, :evidenceCode, :evidenceHash, :evidenceArtifact,
                    :resolvedBy, :manualReason, :receivedAt, :receivedAt, :createdBy
                )
                ON CONFLICT DO NOTHING
                """,
                p);
    }

    @Override
    public Optional<ActionReceipt> findById(
            OrganizationId organizationId, ActionReceiptId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.action_receipt WHERE organization_id = ? AND id = ?",
                this::receipt,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ActionReceipt> findReceiptByAction(
            OrganizationId organizationId, PlannedActionId actionId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.action_receipt
                WHERE organization_id = ? AND action_id = ?
                """,
                this::receipt,
                organizationId.value(), actionId.value()));
    }

    @Override
    public Optional<ActionReceipt> findByExternalIdentity(
            OrganizationId organizationId, ExternalResultIdentity identity) {
        ExternalResultIdentity value = Objects.requireNonNull(identity, "identity");
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.action_receipt
                WHERE organization_id = ? AND connection_id = ?
                  AND external_object_type = ?
                  AND (external_id = ? OR external_business_key = ?)
                """,
                this::receipt,
                organizationId.value(), value.connectionId().value(), value.objectType().name(),
                value.externalId(), value.businessKey()));
    }

    private ActionDispatch dispatch(ResultSet row, int ignored) throws SQLException {
        ActionDispatchId id = new ActionDispatchId(uuid(row, "id"));
        List<ActionDependency> dependencies = jdbc.query(
                """
                SELECT predecessor_action_id
                FROM crewscope.action_dispatch_dependency
                WHERE action_dispatch_id = ? ORDER BY predecessor_action_id
                """,
                (item, index) -> new ActionDependency(
                        new PlannedActionId(uuid(item, "predecessor_action_id"))),
                id.value());
        Optional<ActionClaim> claim = optionalText(row, "claim_worker_id").map(worker -> {
            try {
                return new ActionClaim(
                        id,
                        new PlannedActionId(uuid(row, "action_id")),
                        new ActionWorkerId(worker),
                        new ActionFencingToken(row.getLong("claim_fencing_token")),
                        ActionClaimMode.valueOf(row.getString("claim_mode")),
                        timestamp(row, "claim_acquired_at"),
                        timestamp(row, "claim_last_heartbeat_at"),
                        timestamp(row, "claim_lease_until"));
            } catch (SQLException exception) {
                throw new IllegalStateException("Invalid Action claim row", exception);
            }
        });
        Optional<ActionReceiptReference> receipt = optionalUuid(row, "receipt_id")
                .map(receiptId -> receiptReference(receiptId));
        return ActionDispatch.reconstitute(
                id,
                scope(row),
                new ActionBundleId(uuid(row, "action_bundle_id")),
                new ActionBundleDigest(hash(row, "bundle_digest")),
                new ConfirmationId(uuid(row, "confirmation_id")),
                new PlannedActionId(uuid(row, "action_id")),
                new ActionDigest(hash(row, "action_digest")),
                row.getInt("sequence"),
                dependencies,
                new ActionIdempotencyKey(hash(row, "idempotency_key")),
                timestamp(row, "valid_until"),
                ActionDispatchStatus.valueOf(row.getString("status")),
                claim,
                row.getLong("last_fencing_token"),
                row.getInt("claim_attempts"),
                row.getInt("reconciliation_attempts"),
                timestamp(row, "not_before"),
                receipt,
                optionalText(row, "cancellation_reason").map(ActionCancellationReason::valueOf),
                CompensationDisposition.valueOf(row.getString("compensation_disposition")),
                row.getLong("version"),
                audit(row));
    }

    private ActionReceiptReference receiptReference(UUID receiptId) {
        return one(jdbc.query(
                """
                SELECT id, action_id, action_digest, result
                FROM crewscope.action_receipt WHERE id = ?
                """,
                (row, ignored) -> new ActionReceiptReference(
                        new ActionReceiptId(uuid(row, "id")),
                        new PlannedActionId(uuid(row, "action_id")),
                        new ActionDigest(hash(row, "action_digest")),
                        ActionReceiptResult.valueOf(row.getString("result"))),
                receiptId)).orElseThrow(() -> new IllegalStateException(
                        "Terminal Action Dispatch has no Receipt"));
    }

    private ActionReceipt receipt(ResultSet row, int ignored) throws SQLException {
        PlannedActionId actionId = new PlannedActionId(uuid(row, "action_id"));
        Optional<ActionClaim> claim = optionalText(row, "claim_worker_id").map(worker -> {
            try {
                return new ActionClaim(
                        new ActionDispatchId(uuid(row, "action_dispatch_id")),
                        actionId,
                        new ActionWorkerId(worker),
                        new ActionFencingToken(row.getLong("claim_fencing_token")),
                        ActionClaimMode.valueOf(row.getString("claim_mode")),
                        timestamp(row, "claim_acquired_at"),
                        timestamp(row, "claim_last_heartbeat_at"),
                        timestamp(row, "claim_lease_until"));
            } catch (SQLException exception) {
                throw new IllegalStateException("Invalid Action Receipt claim", exception);
            }
        });
        Optional<ExternalResultIdentity> external = optionalUuid(row, "connection_id")
                .map(connection -> {
                    try {
                        return new ExternalResultIdentity(
                                new ConnectionId(connection),
                                ExternalObjectType.valueOf(row.getString("external_object_type")),
                                row.getString("external_id"),
                                row.getString("external_business_key"));
                    } catch (SQLException exception) {
                        throw new IllegalStateException("Invalid external Receipt identity", exception);
                    }
                });
        return ActionReceipt.reconstitute(
                new ActionReceiptId(uuid(row, "id")),
                scope(row),
                new ActionBundleId(uuid(row, "action_bundle_id")),
                new ActionBundleDigest(hash(row, "bundle_digest")),
                actionId,
                new ActionDigest(hash(row, "action_digest")),
                new ActionIdempotencyKey(hash(row, "idempotency_key")),
                ActionReceiptResult.valueOf(row.getString("result")),
                ActionResultSource.valueOf(row.getString("source")),
                claim,
                external,
                optionalText(row, "target_version"),
                new ActionEvidenceReference(
                        row.getString("evidence_code"),
                        hash(row, "evidence_hash"),
                        optionalUuid(row, "evidence_artifact_id").map(ArtifactId::new)),
                optionalUuid(row, "resolved_by_principal_id").map(PrincipalId::new),
                optionalText(row, "manual_reason").map(ManualResolutionReason::valueOf),
                timestamp(row, "received_at"));
    }

    private static boolean sameLogicalReceipt(ActionReceipt left, ActionReceipt right) {
        return left.scope().equals(right.scope())
                && left.bundleId().equals(right.bundleId())
                && left.bundleDigest().equals(right.bundleDigest())
                && left.actionId().equals(right.actionId())
                && left.actionDigest().equals(right.actionDigest())
                && left.idempotencyKey().equals(right.idempotencyKey())
                && left.result() == right.result()
                && left.source() == right.source()
                && left.claim().equals(right.claim())
                && left.externalIdentity().equals(right.externalIdentity())
                && left.targetVersion().equals(right.targetVersion())
                && left.evidence().equals(right.evidence())
                && left.resolvedByPrincipalId().equals(right.resolvedByPrincipalId())
                && left.manualReason().equals(right.manualReason());
    }

    private static MapSqlParameterSource dispatchParameters(ActionDispatch value) {
        Optional<ActionClaim> claim = value.claim();
        return scope(new MapSqlParameterSource(), value.scope())
                .addValue("id", value.id().value())
                .addValue("bundleId", value.bundleId().value())
                .addValue("bundleDigest", value.bundleDigest().toString())
                .addValue("confirmationId", value.confirmationId().value())
                .addValue("actionId", value.actionId().value())
                .addValue("actionDigest", value.actionDigest().toString())
                .addValue("sequence", value.sequence())
                .addValue("idempotencyKey", value.idempotencyKey().toString())
                .addValue("validUntil", time(value.validUntil()))
                .addValue("status", value.status().name())
                .addValue("claimWorker", claim.map(item -> item.workerId().value()).orElse(null))
                .addValue("claimFencing", claim.map(item -> item.fencingToken().value()).orElse(null))
                .addValue("claimMode", claim.map(item -> item.mode().name()).orElse(null))
                .addValue("claimAcquired", claim.map(item -> time(item.acquiredAt())).orElse(null))
                .addValue("claimHeartbeat", claim.map(item -> time(item.lastHeartbeatAt())).orElse(null))
                .addValue("claimLease", claim.map(item -> time(item.leaseUntil())).orElse(null))
                .addValue("lastFencing", value.lastFencingToken())
                .addValue("claimAttempts", value.claimAttempts())
                .addValue("reconciliationAttempts", value.reconciliationAttempts())
                .addValue("notBefore", time(value.notBefore()))
                .addValue("receiptId", value.receipt().map(item -> item.id().value()).orElse(null))
                .addValue("cancellation", value.cancellationReason().map(Enum::name).orElse(null))
                .addValue("compensation", value.compensationDisposition().name())
                .addValue("version", value.version())
                .addValue("createdAt", time(value.audit().createdAt()))
                .addValue("createdBy", value.audit().createdBy().orElseThrow().value())
                .addValue("updatedAt", time(value.audit().updatedAt()))
                .addValue("updatedBy", value.audit().updatedBy().orElseThrow().value());
    }

    private static MapSqlParameterSource scope(
            MapSqlParameterSource values, WorkItemScope scope) {
        return values.addValue("organizationId", scope.organizationId().value())
                .addValue("teamId", scope.teamId().value())
                .addValue("workspaceId", scope.workspaceId().value())
                .addValue("projectId", scope.projectId().value());
    }

    private static WorkItemScope scope(ResultSet row) throws SQLException {
        return new WorkItemScope(
                new OrganizationId(uuid(row, "organization_id")),
                new TeamId(uuid(row, "team_id")),
                new WorkspaceId(uuid(row, "workspace_id")),
                new WorkProjectId(uuid(row, "project_id")));
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                timestamp(row, "updated_at"));
    }

    private static TaskFactHash hash(ResultSet row, String column) throws SQLException {
        return new TaskFactHash(row.getString(column));
    }

    private static OffsetDateTime time(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    private static Optional<String> optionalText(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getString(column));
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Expected at most one Action execution row");
        }
        return values.stream().findFirst();
    }
}

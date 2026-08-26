package io.crewscope.infrastructure.persistence.notification;

import io.crewscope.application.notification.NotificationPlan;
import io.crewscope.application.notification.NotificationPlanRepository;
import io.crewscope.application.notification.ClaimedNotification;
import io.crewscope.application.notification.NotificationClaim;
import io.crewscope.application.notification.NotificationDispatchRepository;
import io.crewscope.application.notification.NotificationRedeliveryRecord;
import io.crewscope.application.notification.NotificationWorkerId;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.NotifyCollaborationActionParameters;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationAuthorizationDigest;
import io.crewscope.domain.notification.NotificationAuthorizationMode;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationDeduplicationKey;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationInvalidationReason;
import io.crewscope.domain.notification.NotificationPlannedAction;
import io.crewscope.domain.notification.NotificationPlannedActionStatus;
import io.crewscope.domain.notification.NotificationProviderReceiptReference;
import io.crewscope.domain.notification.NotificationReceipt;
import io.crewscope.domain.notification.NotificationReceiptId;
import io.crewscope.domain.notification.NotificationReceiptResult;
import io.crewscope.domain.notification.NotificationRecipientMappingId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableHash;
import io.crewscope.domain.notification.TeamNotificationPolicyId;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.event.projection.InboxEventProjector;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority repository for notification plans and redelivery command receipts. */
@Repository
public class JdbcNotificationPlanRepositoryAdapter
        implements NotificationPlanRepository, NotificationDispatchRepository {

    private static final String PLAN_SELECT = """
            SELECT action.organization_id, action.team_id, action.recipient_member_id,
                   action.projection_name, action.generation, action.action_id,
                   action.intent_id, action.source_identity_hash, action.template_id,
                   action.template_version, action.variable_hash, action.recipient_mapping_id,
                   action.recipient_mapping_version, action.provider_binding_id,
                   action.provider_binding_version, action.connection_id,
                   action.connection_version, action.connection_grant_id,
                   action.connection_grant_version, action.team_policy_id,
                   action.team_policy_version, action.preference_version,
                   action.deduplication_key, action.authorization_digest,
                   action.not_before, action.valid_until, action.status AS action_status,
                   action.invalidation_reason AS action_invalidation_reason,
                   action.redelivery_of AS action_redelivery_of,
                   action.action_digest, action.version AS action_version,
                   intent.item_type, intent.source_type, intent.source_id,
                   intent.source_revision,
                   delivery.delivery_id, delivery.redelivery_of AS delivery_redelivery_of,
                   delivery.status AS delivery_status, delivery.attempt_count,
                   delivery.next_attempt_at,
                   delivery.invalidation_reason AS delivery_invalidation_reason,
                   delivery.version AS delivery_version,
                   delivery.claimed_by, delivery.claim_token, delivery.lease_expires_at,
                   delivery.heartbeat_at, delivery.reconciliation_count,
                   delivery.created_at AS delivery_created_at,
                   delivery.updated_at AS delivery_updated_at,
                   receipt.receipt_id, receipt.result AS receipt_result,
                   receipt.failure_code, receipt.provider_receipt_hash,
                   receipt.provider_message_hash, receipt.evidence_code,
                   receipt.received_at
            FROM crewscope.notification_planned_action action
            JOIN crewscope.notification_intent intent
              ON intent.organization_id = action.organization_id
             AND intent.team_id = action.team_id
             AND intent.recipient_member_id = action.recipient_member_id
             AND intent.projection_name = action.projection_name
             AND intent.generation = action.generation
             AND intent.intent_id = action.intent_id
            JOIN crewscope.notification_delivery delivery
              ON delivery.organization_id = action.organization_id
             AND delivery.action_id = action.action_id
            LEFT JOIN crewscope.notification_receipt receipt
              ON receipt.organization_id = delivery.organization_id
             AND receipt.receipt_id = delivery.receipt_id
            """;

    private final JdbcTemplate jdbc;

    public JdbcNotificationPlanRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationPlan> findByDeduplicationKey(
            OrganizationId organizationId, NotificationDeduplicationKey key) {
        return one(jdbc.query(
                PLAN_SELECT + " WHERE action.organization_id = ? AND action.deduplication_key = ?",
                (row, ignored) -> plan(row),
                requireOrganization(organizationId).value(),
                Objects.requireNonNull(key, "key").toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationPlan> findLatestByIntent(
            OrganizationId organizationId, NotificationIntentId intentId) {
        return one(jdbc.query(
                PLAN_SELECT + """
                         WHERE action.organization_id = ? AND action.intent_id = ?
                         ORDER BY action.created_at DESC, action.action_id DESC
                         LIMIT 1
                        """,
                (row, ignored) -> plan(row),
                requireOrganization(organizationId).value(),
                Objects.requireNonNull(intentId, "intentId").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationPlan> findByDeliveryId(
            OrganizationId organizationId, NotificationDeliveryId deliveryId) {
        return one(jdbc.query(
                PLAN_SELECT + " WHERE action.organization_id = ? AND delivery.delivery_id = ?",
                (row, ignored) -> plan(row),
                requireOrganization(organizationId).value(),
                Objects.requireNonNull(deliveryId, "deliveryId").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationId> findExecutionOrganizations(UtcTimestamp now, int limit) {
        return jdbc.query(
                """
                SELECT DISTINCT delivery.organization_id
                FROM crewscope.notification_delivery delivery
                JOIN crewscope.notification_planned_action action
                  ON action.organization_id = delivery.organization_id
                 AND action.action_id = delivery.action_id
                WHERE action.status = 'PLANNED'
                  AND action.not_before <= ? AND action.valid_until > ?
                  AND (delivery.status = 'READY'
                       OR (delivery.status = 'RETRY_WAIT' AND delivery.next_attempt_at <= ?))
                ORDER BY delivery.organization_id
                LIMIT ?
                """,
                (row, ignored) -> new OrganizationId(
                        row.getObject("organization_id", UUID.class)),
                now.toOffsetDateTime(), now.toOffsetDateTime(), now.toOffsetDateTime(), limit);
    }

    @Override
    @Transactional
    public Optional<ClaimedNotification> claimExecution(
            OrganizationId organizationId,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            Duration leaseDuration) {
        OrganizationId organization = requireOrganization(organizationId);
        List<WorkerRow> rows = jdbc.query(
                PLAN_SELECT + """
                 WHERE action.organization_id = ? AND action.status = 'PLANNED'
                   AND action.not_before <= ? AND action.valid_until > ?
                   AND (delivery.status = 'READY'
                        OR (delivery.status = 'RETRY_WAIT' AND delivery.next_attempt_at <= ?))
                 ORDER BY delivery.created_at, delivery.delivery_id
                 FOR UPDATE OF delivery SKIP LOCKED
                 LIMIT 1
                """,
                (row, ignored) -> workerRow(row),
                organization.value(), now.toOffsetDateTime(), now.toOffsetDateTime(),
                now.toOffsetDateTime());
        Optional<WorkerRow> candidate = one(rows);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        WorkerRow current = candidate.orElseThrow();
        NotificationDelivery started = current.plan().delivery().start(
                current.plan().delivery().version(), current.plan().action(), now);
        long token = current.claimToken() + 1;
        UtcTimestamp leaseUntil = plus(now, leaseDuration);
        updateClaimTransition(
                organization, current.plan().delivery(), started, current.claimToken(), token,
                current.reconciliationCount(), workerId, now, leaseUntil);
        NotificationPlan claimedPlan = new NotificationPlan(current.plan().action(), started);
        return Optional.of(new ClaimedNotification(
                claimedPlan,
                new NotificationClaim(
                        started.id(), workerId, token, started.version(),
                        current.reconciliationCount(), leaseUntil)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationId> findReconciliationOrganizations(
            UtcTimestamp now, Duration retryDelay, int limit) {
        UtcTimestamp due = UtcTimestamp.from(now.value().minus(retryDelay));
        return jdbc.query(
                """
                SELECT DISTINCT organization_id
                FROM crewscope.notification_delivery
                WHERE (status = 'UNKNOWN' AND updated_at <= ?)
                   OR (status IN ('RUNNING', 'RECONCILING') AND lease_expires_at <= ?)
                ORDER BY organization_id
                LIMIT ?
                """,
                (row, ignored) -> new OrganizationId(
                        row.getObject("organization_id", UUID.class)),
                due.toOffsetDateTime(), now.toOffsetDateTime(), limit);
    }

    @Override
    @Transactional
    public Optional<ClaimedNotification> claimReconciliation(
            OrganizationId organizationId,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            Duration leaseDuration,
            Duration retryDelay) {
        OrganizationId organization = requireOrganization(organizationId);
        UtcTimestamp due = UtcTimestamp.from(now.value().minus(retryDelay));
        List<WorkerRow> rows = jdbc.query(
                PLAN_SELECT + """
                 WHERE action.organization_id = ? AND action.status = 'PLANNED'
                   AND ((delivery.status = 'UNKNOWN' AND delivery.updated_at <= ?)
                        OR (delivery.status IN ('RUNNING', 'RECONCILING')
                            AND delivery.lease_expires_at <= ?))
                 ORDER BY delivery.updated_at, delivery.delivery_id
                 FOR UPDATE OF delivery SKIP LOCKED
                 LIMIT 1
                """,
                (row, ignored) -> workerRow(row),
                organization.value(), due.toOffsetDateTime(), now.toOffsetDateTime());
        Optional<WorkerRow> candidate = one(rows);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        WorkerRow current = candidate.orElseThrow();
        NotificationDelivery base = current.plan().delivery();
        long token = current.claimToken();
        if (base.status() == NotificationDeliveryStatus.RUNNING) {
            // A crashed writer may already have crossed the Provider boundary. Persist UNKNOWN
            // before taking a query-only claim, without ever issuing another write.
            NotificationDelivery unknown = base.markUnknown(base.version(), now);
            updateUnclaimedTransition(
                    organization, base, unknown, token, current.reconciliationCount());
            base = unknown;
        }
        NotificationDelivery reconciling = base.status() == NotificationDeliveryStatus.UNKNOWN
                ? base.beginReconciliation(base.version(), now)
                : base.reclaimReconciliation(base.version(), now);
        int reconciliationCount = current.reconciliationCount() + 1;
        long nextToken = token + 1;
        UtcTimestamp leaseUntil = plus(now, leaseDuration);
        updateClaimTransition(
                organization, base, reconciling, token, nextToken, reconciliationCount,
                workerId, now, leaseUntil);
        NotificationPlan claimedPlan = new NotificationPlan(current.plan().action(), reconciling);
        return Optional.of(new ClaimedNotification(
                claimedPlan,
                new NotificationClaim(
                        reconciling.id(), workerId, nextToken, reconciling.version(),
                        reconciliationCount, leaseUntil)));
    }

    @Override
    @Transactional
    public NotificationPlan updateClaimed(
            OrganizationId organizationId,
            NotificationClaim claim,
            NotificationPlan outcome,
            UtcTimestamp authoritativeNow) {
        OrganizationId organization = requireOrganization(organizationId);
        NotificationPlan value = Objects.requireNonNull(outcome, "outcome");
        NotificationDelivery delivery = value.delivery();
        if (!delivery.id().equals(claim.deliveryId())
                || delivery.version() != claim.deliveryVersion() + 1) {
            throw new IllegalArgumentException("Notification outcome does not follow its claim");
        }
        updateActionIfChanged(organization, value);
        insertReceipt(organization, delivery.receipt());
        int updated = jdbc.update(
                """
                UPDATE crewscope.notification_delivery
                SET status = ?, attempt_count = ?, next_attempt_at = ?,
                    invalidation_reason = ?, receipt_id = ?, version = ?, updated_at = ?,
                    claimed_by = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                WHERE organization_id = ? AND delivery_id = ? AND version = ?
                  AND claimed_by = ? AND claim_token = ? AND lease_expires_at > ?
                  AND status IN ('RUNNING', 'RECONCILING')
                """,
                delivery.status().name(), delivery.attemptCount(),
                delivery.nextAttemptAt().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                delivery.invalidationReason().map(Enum::name).orElse(null),
                delivery.receipt().map(receipt -> receipt.id().value()).orElse(null),
                delivery.version(), delivery.updatedAt().toOffsetDateTime(),
                organization.value(), delivery.id().value(), claim.deliveryVersion(),
                claim.workerId().value(), claim.fencingToken(),
                authoritativeNow.toOffsetDateTime());
        if (updated != 1) {
            throw deliveryConflict(organization, delivery);
        }
        return value;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationRedeliveryRecord> findRedelivery(
            OrganizationId organizationId, NotificationRedeliveryCommandId commandId) {
        OrganizationId organization = requireOrganization(organizationId);
        List<RedeliveryPointer> pointers = jdbc.query(
                """
                SELECT original_delivery_id, replacement_delivery_id
                FROM crewscope.notification_redelivery_receipt
                WHERE organization_id = ? AND command_id = ?
                """,
                (row, ignored) -> new RedeliveryPointer(
                        new NotificationDeliveryId(row.getObject("original_delivery_id", UUID.class)),
                        new NotificationDeliveryId(row.getObject("replacement_delivery_id", UUID.class))),
                organization.value(), Objects.requireNonNull(commandId, "commandId").value());
        Optional<RedeliveryPointer> pointer = one(pointers);
        if (pointer.isEmpty()) {
            return Optional.empty();
        }
        RedeliveryPointer value = pointer.orElseThrow();
        NotificationPlan plan = findByDeliveryId(organization, value.replacementDeliveryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Notification redelivery receipt points to a missing plan"));
        return Optional.of(new NotificationRedeliveryRecord(
                commandId, value.originalDeliveryId(), plan));
    }

    @Override
    @Transactional
    public NotificationPlan save(NotificationPlan plan) {
        NotificationPlan value = Objects.requireNonNull(plan, "plan");
        OrganizationId organization = value.action().parameters().organizationId();
        Optional<NotificationPlan> existing = findByDeduplicationKey(
                organization, value.action().authority().deduplicationKey());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        IntentCoordinate intent = currentIntent(value.action());
        int inserted = insertAction(value, intent);
        if (inserted == 0) {
            return findByDeduplicationKey(
                            organization, value.action().authority().deduplicationKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent notification action insert did not become visible"));
        }
        insertDelivery(organization, value.delivery());
        return value;
    }

    @Override
    @Transactional
    public NotificationPlan update(NotificationPlan plan) {
        NotificationPlan value = Objects.requireNonNull(plan, "plan");
        NotificationPlannedAction action = value.action();
        NotificationDelivery delivery = value.delivery();
        OrganizationId organization = action.parameters().organizationId();
        updateActionIfChanged(organization, value);
        insertReceipt(organization, delivery.receipt());
        int updatedDelivery = jdbc.update(
                """
                UPDATE crewscope.notification_delivery
                SET status = ?, attempt_count = ?, next_attempt_at = ?,
                    invalidation_reason = ?, receipt_id = ?, version = ?, updated_at = ?,
                    claimed_by = NULL, lease_expires_at = NULL, heartbeat_at = NULL
                WHERE organization_id = ? AND delivery_id = ? AND version = ?
                """,
                delivery.status().name(), delivery.attemptCount(),
                delivery.nextAttemptAt().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                delivery.invalidationReason().map(Enum::name).orElse(null),
                delivery.receipt().map(receipt -> receipt.id().value()).orElse(null),
                delivery.version(), delivery.updatedAt().toOffsetDateTime(),
                organization.value(), delivery.id().value(), delivery.version() - 1);
        if (updatedDelivery != 1) {
            throw deliveryConflict(organization, delivery);
        }
        return value;
    }

    @Override
    @Transactional
    public NotificationPlan replaceDrifted(
            NotificationPlan invalidatedPlan, NotificationPlan replacementPlan) {
        update(Objects.requireNonNull(invalidatedPlan, "invalidatedPlan"));
        return save(Objects.requireNonNull(replacementPlan, "replacementPlan"));
    }

    @Override
    @Transactional
    public NotificationRedeliveryRecord saveRedelivery(NotificationRedeliveryRecord record) {
        NotificationRedeliveryRecord value = Objects.requireNonNull(record, "record");
        OrganizationId organization = value.plan().action().parameters().organizationId();
        Optional<NotificationRedeliveryRecord> existing = findRedelivery(
                organization, value.commandId());
        if (existing.isPresent()) {
            return requireSameRedelivery(value, existing.orElseThrow());
        }
        NotificationPlan saved = save(value.plan());
        jdbc.update(
                """
                INSERT INTO crewscope.notification_redelivery_receipt (
                    organization_id, command_id, original_delivery_id,
                    replacement_delivery_id, created_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, command_id) DO NOTHING
                """,
                organization.value(), value.commandId().value(),
                value.originalDeliveryId().value(), saved.delivery().id().value(),
                saved.delivery().createdAt().toOffsetDateTime());
        NotificationRedeliveryRecord committed = findRedelivery(organization, value.commandId())
                .orElseThrow(() -> new IllegalStateException(
                        "Notification redelivery receipt was not persisted"));
        return requireSameRedelivery(value, committed);
    }

    private int insertAction(NotificationPlan plan, IntentCoordinate intent) {
        NotificationPlannedAction action = plan.action();
        NotificationAuthorizationSnapshot authority = action.authority();
        NotifyCollaborationActionParameters parameters = action.parameters();
        return jdbc.update(
                """
                INSERT INTO crewscope.notification_planned_action (
                    organization_id, team_id, recipient_member_id, projection_name, generation,
                    action_id, intent_id, source_identity_hash, template_id, template_version,
                    variable_hash, recipient_mapping_id, recipient_mapping_version,
                    provider_binding_id, provider_binding_version, connection_id,
                    connection_version, connection_grant_id, connection_grant_version,
                    team_policy_id, team_policy_version, preference_version,
                    deduplication_key, authorization_digest, not_before, valid_until,
                    status, invalidation_reason, redelivery_of, action_digest,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, deduplication_key) DO NOTHING
                """,
                parameters.organizationId().value(), parameters.teamId().value(),
                parameters.recipientMemberId().value(), intent.projectionName(), intent.generation(),
                action.id().value(), parameters.intentId().value(),
                TaskFactHash.sha256(authority.sourceKey().canonicalIdentity()).toString(),
                parameters.template().templateId().value(), parameters.template().version().value(),
                parameters.variableHash().toString(), authority.recipientMappingId().value(),
                authority.recipientMappingVersion(), authority.providerBindingId().value(),
                authority.providerBindingVersion(), authority.connectionId().value(),
                authority.connectionVersion(), authority.grantId().value(), authority.grantVersion(),
                authority.teamPolicyId().value(), authority.teamPolicyVersion(),
                authority.preferenceVersion(), authority.deduplicationKey().toString(),
                authority.digest().toString(), action.notBefore().toOffsetDateTime(),
                action.validUntil().toOffsetDateTime(), action.status().name(),
                action.invalidationReason().map(Enum::name).orElse(null),
                action.redeliveryOf().map(id -> id.value()).orElse(null), action.digest().toString(),
                action.version(), plan.delivery().createdAt().toOffsetDateTime(),
                plan.delivery().updatedAt().toOffsetDateTime());
    }

    private void insertDelivery(
            OrganizationId organizationId, NotificationDelivery delivery) {
        jdbc.update(
                """
                INSERT INTO crewscope.notification_delivery (
                    organization_id, delivery_id, action_id, action_digest, deduplication_key,
                    redelivery_of, status, attempt_count, next_attempt_at,
                    invalidation_reason, receipt_id, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId.value(), delivery.id().value(), delivery.actionId().value(),
                delivery.actionDigest().toString(), delivery.deduplicationKey().toString(),
                delivery.redeliveryOf().map(id -> id.value()).orElse(null), delivery.status().name(),
                delivery.attemptCount(),
                delivery.nextAttemptAt().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                delivery.invalidationReason().map(Enum::name).orElse(null),
                delivery.receipt().map(receipt -> receipt.id().value()).orElse(null),
                delivery.version(), delivery.createdAt().toOffsetDateTime(),
                delivery.updatedAt().toOffsetDateTime());
        insertReceipt(organizationId, delivery.receipt());
    }

    private void insertReceipt(
            OrganizationId organizationId, Optional<NotificationReceipt> receipt) {
        receipt.ifPresent(value -> jdbc.update(
                """
                INSERT INTO crewscope.notification_receipt (
                    organization_id, receipt_id, delivery_id, action_id, action_digest,
                    deduplication_key, result, failure_code, provider_receipt_hash,
                    provider_message_hash, evidence_code, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, delivery_id) DO NOTHING
                """,
                organizationId.value(), value.id().value(), value.deliveryId().value(),
                value.actionId().value(), value.actionDigest().toString(),
                value.deduplicationKey().toString(), value.result().name(),
                value.failureCode().map(Enum::name).orElse(null),
                value.providerReference().map(reference -> reference.safeHash().toString()).orElse(null),
                value.providerMessageHash().map(TaskFactHash::toString).orElse(null),
                value.evidenceCode(), value.receivedAt().toOffsetDateTime()));
    }

    private void updateActionIfChanged(OrganizationId organization, NotificationPlan plan) {
        NotificationPlannedAction action = plan.action();
        if (action.version() == 0) {
            return;
        }
        int updated = jdbc.update(
                """
                UPDATE crewscope.notification_planned_action
                SET status = ?, invalidation_reason = ?, version = ?, updated_at = ?
                WHERE organization_id = ? AND action_id = ? AND version = ?
                """,
                action.status().name(), action.invalidationReason().map(Enum::name).orElse(null),
                action.version(), plan.delivery().updatedAt().toOffsetDateTime(),
                organization.value(), action.id().value(), action.version() - 1);
        if (updated != 1) {
            throw actionConflict(organization, action);
        }
    }

    private void updateClaimTransition(
            OrganizationId organization,
            NotificationDelivery previous,
            NotificationDelivery next,
            long previousToken,
            long nextToken,
            int reconciliationCount,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            UtcTimestamp leaseUntil) {
        int updated = jdbc.update(
                """
                UPDATE crewscope.notification_delivery
                SET status = ?, attempt_count = ?, next_attempt_at = ?,
                    invalidation_reason = ?, receipt_id = ?, version = ?, updated_at = ?,
                    claimed_by = ?, claim_token = ?, lease_expires_at = ?, heartbeat_at = ?,
                    reconciliation_count = ?
                WHERE organization_id = ? AND delivery_id = ? AND version = ?
                  AND claim_token = ?
                """,
                next.status().name(), next.attemptCount(),
                next.nextAttemptAt().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                next.invalidationReason().map(Enum::name).orElse(null),
                next.receipt().map(receipt -> receipt.id().value()).orElse(null),
                next.version(), next.updatedAt().toOffsetDateTime(), workerId.value(), nextToken,
                leaseUntil.toOffsetDateTime(), now.toOffsetDateTime(), reconciliationCount,
                organization.value(), previous.id().value(), previous.version(), previousToken);
        if (updated != 1) {
            throw deliveryConflict(organization, next);
        }
    }

    private void updateUnclaimedTransition(
            OrganizationId organization,
            NotificationDelivery previous,
            NotificationDelivery next,
            long claimToken,
            int reconciliationCount) {
        int updated = jdbc.update(
                """
                UPDATE crewscope.notification_delivery
                SET status = ?, attempt_count = ?, next_attempt_at = ?,
                    invalidation_reason = ?, receipt_id = ?, version = ?, updated_at = ?,
                    claimed_by = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
                    reconciliation_count = ?
                WHERE organization_id = ? AND delivery_id = ? AND version = ?
                  AND claim_token = ?
                """,
                next.status().name(), next.attemptCount(),
                next.nextAttemptAt().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                next.invalidationReason().map(Enum::name).orElse(null),
                next.receipt().map(receipt -> receipt.id().value()).orElse(null),
                next.version(), next.updatedAt().toOffsetDateTime(), reconciliationCount,
                organization.value(), previous.id().value(), previous.version(), claimToken);
        if (updated != 1) {
            throw deliveryConflict(organization, next);
        }
    }

    private IntentCoordinate currentIntent(NotificationPlannedAction action) {
        NotifyCollaborationActionParameters parameters = action.parameters();
        List<IntentCoordinate> rows = jdbc.query(
                """
                SELECT intent.projection_name, intent.generation
                FROM crewscope.projection_pointer pointer
                JOIN crewscope.notification_intent intent
                  ON intent.organization_id = pointer.organization_id
                 AND intent.projection_name = pointer.projection_name
                 AND intent.generation = pointer.active_generation
                WHERE pointer.organization_id = ? AND pointer.projection_name = ?
                  AND intent.team_id = ? AND intent.recipient_member_id = ?
                  AND intent.intent_id = ? AND intent.item_type = ?
                  AND intent.source_type = ? AND intent.source_id = ?
                  AND intent.source_revision = ? AND intent.template_id = ?
                  AND intent.template_version = ? AND intent.variable_hash = ?
                """,
                (row, ignored) -> new IntentCoordinate(
                        row.getString("projection_name"), row.getLong("generation")),
                parameters.organizationId().value(), InboxEventProjector.PROJECTION_NAME.value(),
                parameters.teamId().value(), parameters.recipientMemberId().value(),
                parameters.intentId().value(), action.authority().sourceKey().itemType().name(),
                action.authority().sourceKey().sourceType().name(),
                action.authority().sourceKey().sourceId(),
                action.authority().sourceKey().sourceRevision().value(),
                parameters.template().templateId().value(), parameters.template().version().value(),
                parameters.variableHash().toString());
        return one(rows).orElseThrow(() -> new IllegalStateException(
                "Notification plan requires the exact current intent Generation"));
    }

    private NotificationPlan plan(ResultSet row) throws SQLException {
        OrganizationId organizationId = new OrganizationId(
                row.getObject("organization_id", UUID.class));
        TeamId teamId = new TeamId(row.getObject("team_id", UUID.class));
        TeamMemberId memberId = new TeamMemberId(
                row.getObject("recipient_member_id", UUID.class));
        NotificationIntentId intentId = new NotificationIntentId(
                row.getObject("intent_id", UUID.class));
        InboxSourceKey sourceKey = new InboxSourceKey(
                organizationId, memberId, InboxItemType.valueOf(row.getString("item_type")),
                InboxSourceType.valueOf(row.getString("source_type")),
                row.getObject("source_id", UUID.class),
                new InboxSourceRevision(row.getLong("source_revision")));
        String persistedSourceHash = row.getString("source_identity_hash");
        if (!TaskFactHash.sha256(sourceKey.canonicalIdentity()).toString()
                .equals(persistedSourceHash)) {
            throw new IllegalStateException("Notification source identity hash is invalid");
        }
        NotificationTemplateRef template = new NotificationTemplateRef(
                new NotificationTemplateId(row.getObject("template_id", UUID.class)),
                new NotificationTemplateVersion(row.getLong("template_version")));
        NotificationVariableHash variableHash = new NotificationVariableHash(
                new TaskFactHash(row.getString("variable_hash")));
        NotificationDeduplicationKey deduplicationKey = new NotificationDeduplicationKey(
                new TaskFactHash(row.getString("deduplication_key")));
        NotificationAuthorizationSnapshot authority = NotificationAuthorizationSnapshot.reconstitute(
                NotificationAuthorizationMode.POLICY_PREAUTHORIZED, intentId, sourceKey, template,
                variableHash,
                new NotificationRecipientMappingId(
                        row.getObject("recipient_mapping_id", UUID.class)),
                row.getLong("recipient_mapping_version"),
                new ProviderBindingId(row.getObject("provider_binding_id", UUID.class)),
                row.getLong("provider_binding_version"),
                new ConnectionId(row.getObject("connection_id", UUID.class)),
                row.getLong("connection_version"),
                new ConnectionGrantId(row.getObject("connection_grant_id", UUID.class)),
                row.getLong("connection_grant_version"),
                new TeamNotificationPolicyId(row.getObject("team_policy_id", UUID.class)),
                row.getLong("team_policy_version"), row.getLong("preference_version"),
                deduplicationKey, new NotificationAuthorizationDigest(
                        new TaskFactHash(row.getString("authorization_digest"))));
        NotifyCollaborationActionParameters parameters = new NotifyCollaborationActionParameters(
                organizationId, teamId, memberId, intentId, template, variableHash,
                deduplicationKey);
        NotificationPlannedAction action = NotificationPlannedAction.reconstitute(
                new PlannedActionId(row.getObject("action_id", UUID.class)), parameters, authority,
                timestamp(row, "not_before"), timestamp(row, "valid_until"),
                NotificationPlannedActionStatus.valueOf(row.getString("action_status")),
                optionalEnum(row.getString("action_invalidation_reason"),
                        NotificationInvalidationReason.class),
                optionalId(row, "action_redelivery_of"),
                new ActionDigest(new TaskFactHash(row.getString("action_digest"))),
                row.getLong("action_version"));
        NotificationDeliveryId deliveryId = new NotificationDeliveryId(
                row.getObject("delivery_id", UUID.class));
        Optional<NotificationReceipt> receipt = receipt(row, deliveryId, action);
        NotificationDelivery delivery = NotificationDelivery.reconstitute(
                deliveryId, action.id(), action.digest(), deduplicationKey,
                optionalId(row, "delivery_redelivery_of"),
                NotificationDeliveryStatus.valueOf(row.getString("delivery_status")),
                row.getInt("attempt_count"), optionalTimestamp(row, "next_attempt_at"),
                optionalEnum(row.getString("delivery_invalidation_reason"),
                        NotificationInvalidationReason.class),
                receipt, timestamp(row, "delivery_created_at"),
                timestamp(row, "delivery_updated_at"), row.getLong("delivery_version"));
        return new NotificationPlan(action, delivery);
    }

    private WorkerRow workerRow(ResultSet row) throws SQLException {
        return new WorkerRow(
                plan(row), row.getLong("claim_token"), row.getInt("reconciliation_count"));
    }

    private Optional<NotificationReceipt> receipt(
            ResultSet row, NotificationDeliveryId deliveryId, NotificationPlannedAction action)
            throws SQLException {
        UUID receiptId = row.getObject("receipt_id", UUID.class);
        if (receiptId == null) {
            return Optional.empty();
        }
        String failure = row.getString("failure_code");
        String providerReceipt = row.getString("provider_receipt_hash");
        String providerMessage = row.getString("provider_message_hash");
        return Optional.of(new NotificationReceipt(
                new NotificationReceiptId(receiptId), deliveryId, action.id(), action.digest(),
                action.authority().deduplicationKey(),
                NotificationReceiptResult.valueOf(row.getString("receipt_result")),
                optionalEnum(failure, NotificationFailureCode.class),
                providerReceipt == null
                        ? Optional.empty()
                        : Optional.of(new NotificationProviderReceiptReference(
                                new TaskFactHash(providerReceipt))),
                providerMessage == null
                        ? Optional.empty()
                        : Optional.of(new TaskFactHash(providerMessage)),
                row.getString("evidence_code"), timestamp(row, "received_at")));
    }

    private OptimisticLockConflictException actionConflict(
            OrganizationId organizationId, NotificationPlannedAction action) {
        return new OptimisticLockConflictException(
                "NotificationPlannedAction", action.id(), action.version() - 1,
                actualVersion("notification_planned_action", "action_id",
                        organizationId, action.id().value()));
    }

    private OptimisticLockConflictException deliveryConflict(
            OrganizationId organizationId, NotificationDelivery delivery) {
        return new OptimisticLockConflictException(
                "NotificationDelivery", delivery.id(), delivery.version() - 1,
                actualVersion("notification_delivery", "delivery_id",
                        organizationId, delivery.id().value()));
    }

    private long actualVersion(
            String table, String idColumn, OrganizationId organizationId, UUID id) {
        Long actual = jdbc.query(
                "SELECT version FROM crewscope." + table
                        + " WHERE organization_id = ? AND " + idColumn + " = ?",
                result -> result.next() ? result.getLong("version") : null,
                organizationId.value(), id);
        return actual == null ? 0 : actual;
    }

    private static NotificationRedeliveryRecord requireSameRedelivery(
            NotificationRedeliveryRecord requested, NotificationRedeliveryRecord committed) {
        if (!requested.originalDeliveryId().equals(committed.originalDeliveryId())
                || !requested.plan().delivery().id().equals(committed.plan().delivery().id())) {
            throw new IllegalStateException(
                    "Notification redelivery command is already bound to another delivery");
        }
        return committed;
    }

    private static OrganizationId requireOrganization(OrganizationId organizationId) {
        return Objects.requireNonNull(organizationId, "organizationId");
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static Optional<UtcTimestamp> optionalTimestamp(ResultSet row, String column)
            throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? Optional.empty() : Optional.of(UtcTimestamp.from(value));
    }

    private static Optional<NotificationDeliveryId> optionalId(ResultSet row, String column)
            throws SQLException {
        UUID value = row.getObject(column, UUID.class);
        return value == null ? Optional.empty() : Optional.of(new NotificationDeliveryId(value));
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(
            String value, Class<E> type) {
        return value == null ? Optional.empty() : Optional.of(Enum.valueOf(type, value));
    }

    private static <T> Optional<T> one(List<T> rows) {
        if (rows.size() > 1) {
            throw new IllegalStateException("Notification repository query returned multiple rows");
        }
        return rows.stream().findFirst();
    }

    private static UtcTimestamp plus(UtcTimestamp timestamp, Duration duration) {
        return UtcTimestamp.from(timestamp.value().plus(duration));
    }

    private record IntentCoordinate(String projectionName, long generation) {}

    private record RedeliveryPointer(
            NotificationDeliveryId originalDeliveryId,
            NotificationDeliveryId replacementDeliveryId) {}

    private record WorkerRow(
            NotificationPlan plan, long claimToken, int reconciliationCount) {}
}

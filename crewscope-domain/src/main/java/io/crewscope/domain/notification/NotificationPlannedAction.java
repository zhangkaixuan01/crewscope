package io.crewscope.domain.notification;

import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionRiskLevel;
import io.crewscope.domain.action.NotifyCollaborationActionParameters;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Independent low-risk notification action authorized without an M5 Confirmation. */
public final class NotificationPlannedAction {

    private final PlannedActionId id;
    private final NotifyCollaborationActionParameters parameters;
    private final NotificationAuthorizationSnapshot authority;
    private final ActionRiskLevel risk;
    private final UtcTimestamp notBefore;
    private final UtcTimestamp validUntil;
    private final NotificationPlannedActionStatus status;
    private final Optional<NotificationInvalidationReason> invalidationReason;
    private final Optional<NotificationDeliveryId> redeliveryOf;
    private final ActionDigest digest;
    private final long version;

    private NotificationPlannedAction(
            PlannedActionId id,
            NotifyCollaborationActionParameters parameters,
            NotificationAuthorizationSnapshot authority,
            UtcTimestamp notBefore,
            UtcTimestamp validUntil,
            NotificationPlannedActionStatus status,
            Optional<NotificationInvalidationReason> invalidationReason,
            Optional<NotificationDeliveryId> redeliveryOf,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.risk = ActionRiskLevel.LOW_RISK_WRITE;
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        this.status = Objects.requireNonNull(status, "status");
        this.invalidationReason = Objects.requireNonNull(invalidationReason, "invalidationReason");
        this.redeliveryOf = Objects.requireNonNull(redeliveryOf, "redeliveryOf");
        if (notBefore.compareTo(validUntil) >= 0) {
            throw new DomainValidationException(
                    "notificationPlannedAction.validUntil", "must be after notBefore");
        }
        if (!parameters.intentId().equals(authority.intentId())
                || !parameters.template().equals(authority.template())
                || !parameters.variableHash().equals(authority.variableHash())
                || !parameters.deduplicationKey().equals(authority.deduplicationKey())) {
            throw new DomainValidationException(
                    "notificationPlannedAction.authority", "must bind the exact action parameters");
        }
        if ((status == NotificationPlannedActionStatus.INVALIDATED)
                != invalidationReason.isPresent()) {
            throw new DomainValidationException(
                    "notificationPlannedAction.status", "must match invalidation reason presence");
        }
        if (version < 0) {
            throw new DomainValidationException(
                    "notificationPlannedAction.version", "must not be negative");
        }
        this.version = version;
        this.digest = calculateDigest();
    }

    public static NotificationPlannedAction plan(
            NotificationAuthorizationFacts facts,
            NotificationAuthorizationSnapshot authority,
            UtcTimestamp notBefore,
            UtcTimestamp validUntil,
            Optional<NotificationDeliveryId> redeliveryOf) {
        NotificationIntent intent = Objects.requireNonNull(facts, "facts").intent();
        NotificationAuthorizationSnapshot snapshot = Objects.requireNonNull(authority, "authority");
        PlannedActionId id = deterministicId(snapshot.deduplicationKey());
        NotifyCollaborationActionParameters parameters = new NotifyCollaborationActionParameters(
                intent.organizationId(),
                intent.teamId(),
                intent.recipientMemberId(),
                intent.id(),
                intent.template(),
                intent.variables().hash(),
                snapshot.deduplicationKey());
        return new NotificationPlannedAction(
                id,
                parameters,
                snapshot,
                notBefore,
                validUntil,
                NotificationPlannedActionStatus.PLANNED,
                Optional.empty(),
                redeliveryOf,
                0);
    }

    /** Rebuilds a persisted action and rejects identity or digest tampering. */
    public static NotificationPlannedAction reconstitute(
            PlannedActionId id,
            NotifyCollaborationActionParameters parameters,
            NotificationAuthorizationSnapshot authority,
            UtcTimestamp notBefore,
            UtcTimestamp validUntil,
            NotificationPlannedActionStatus status,
            Optional<NotificationInvalidationReason> invalidationReason,
            Optional<NotificationDeliveryId> redeliveryOf,
            ActionDigest persistedDigest,
            long version) {
        NotificationPlannedAction action = new NotificationPlannedAction(
                id, parameters, authority, notBefore, validUntil, status, invalidationReason,
                redeliveryOf, version);
        if (!deterministicId(authority.deduplicationKey()).equals(action.id)
                || !action.digest.equals(Objects.requireNonNull(persistedDigest, "persistedDigest"))) {
            throw new DomainValidationException(
                    "notificationPlannedAction.digest",
                    "does not match the persisted action coordinates");
        }
        return action;
    }

    public NotificationPlannedAction invalidate(
            long expectedVersion, NotificationInvalidationReason reason) {
        requireVersion(expectedVersion);
        if (status == NotificationPlannedActionStatus.INVALIDATED) {
            if (invalidationReason.filter(reason::equals).isPresent()) {
                return this;
            }
            throw new IllegalStateException("Notification action is already invalidated");
        }
        return new NotificationPlannedAction(
                id, parameters, authority, notBefore, validUntil,
                NotificationPlannedActionStatus.INVALIDATED, Optional.of(reason), redeliveryOf,
                version + 1);
    }

    private ActionDigest calculateDigest() {
        String canonical = "notification-action-v1"
                + encode(
                        id.toString(),
                        parameters.kind().name(),
                        parameters.organizationId().toString(),
                        parameters.teamId().toString(),
                        parameters.recipientMemberId().toString(),
                        authority.digest().toString(),
                        risk.name(),
                        notBefore.toString(),
                        validUntil.toString(),
                        redeliveryOf.map(Object::toString).orElse(""));
        return new ActionDigest(TaskFactHash.sha256(canonical));
    }

    private static PlannedActionId deterministicId(NotificationDeduplicationKey key) {
        return new PlannedActionId(UUID.nameUUIDFromBytes(
                ("notification-action-v1:" + key).getBytes(StandardCharsets.UTF_8)));
    }

    private void requireVersion(long expected) {
        if (expected != version) {
            throw new IllegalStateException("Notification action version conflict");
        }
    }

    private static String encode(String... values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : values) {
            encoded.append('|').append(value.length()).append(':').append(value);
        }
        return encoded.toString();
    }

    public PlannedActionId id() { return id; }
    public NotifyCollaborationActionParameters parameters() { return parameters; }
    public NotificationAuthorizationSnapshot authority() { return authority; }
    public ActionRiskLevel risk() { return risk; }
    public UtcTimestamp notBefore() { return notBefore; }
    public UtcTimestamp validUntil() { return validUntil; }
    public NotificationPlannedActionStatus status() { return status; }
    public Optional<NotificationInvalidationReason> invalidationReason() { return invalidationReason; }
    public Optional<NotificationDeliveryId> redeliveryOf() { return redeliveryOf; }
    public ActionDigest digest() { return digest; }
    public long version() { return version; }
}

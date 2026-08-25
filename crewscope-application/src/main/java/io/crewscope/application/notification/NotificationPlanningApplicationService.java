package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationIntent;
import io.crewscope.domain.notification.NotificationInvalidationReason;
import io.crewscope.domain.notification.NotificationPlannedAction;
import io.crewscope.domain.notification.NotificationPreferenceDecision;
import io.crewscope.domain.notification.NotificationReceipt;
import io.crewscope.domain.notification.NotificationReceiptId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Plans deduplicated policy-preauthorized notifications and explicit failure redeliveries. */
public final class NotificationPlanningApplicationService {

    private final NotificationTemplateCatalog templates;
    private final NotificationAuthorizationFactsResolver factsResolver;
    private final NotificationRecipientAuthorization recipientAuthorization;
    private final NotificationPlanRepository plans;
    private final TimeProvider timeProvider;
    private final Duration validity;

    public NotificationPlanningApplicationService(
            NotificationTemplateCatalog templates,
            NotificationAuthorizationFactsResolver factsResolver,
            NotificationRecipientAuthorization recipientAuthorization,
            NotificationPlanRepository plans,
            TimeProvider timeProvider,
            Duration validity) {
        this.templates = Objects.requireNonNull(templates, "templates");
        this.factsResolver = Objects.requireNonNull(factsResolver, "factsResolver");
        this.recipientAuthorization = Objects.requireNonNull(
                recipientAuthorization, "recipientAuthorization");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.validity = requireValidity(validity);
    }

    public NotificationPlan plan(NotificationIntent intent) {
        NotificationIntent requiredIntent = Objects.requireNonNull(intent, "intent");
        templates.requireCurrentPublished(requiredIntent.template());
        NotificationAuthorizationFacts facts = factsResolver.resolveCurrent(requiredIntent.id());
        requireExactIntent(requiredIntent, facts);
        UtcTimestamp now = timeProvider.now();
        UtcTimestamp notBefore = requirePreference(facts, now);
        NotificationAuthorizationSnapshot snapshot =
                NotificationAuthorizationSnapshot.captureAutomatic(facts);
        Optional<NotificationPlan> duplicate = plans.findByDeduplicationKey(
                requiredIntent.organizationId(), snapshot.deduplicationKey());
        if (duplicate.isPresent()) {
            return duplicate.orElseThrow();
        }
        NotificationPlan replacement = newPlan(
                facts, snapshot, notBefore, now, Optional.empty());
        return replaceDriftedPlan(facts, snapshot, replacement, now)
                .orElseGet(() -> plans.save(replacement));
    }

    public NotificationRedeliveryRecord redeliver(RedeliverNotificationCommand command) {
        RedeliverNotificationCommand required = Objects.requireNonNull(command, "command");
        Optional<NotificationRedeliveryRecord> replay = plans.findRedelivery(
                required.organizationId(), required.commandId());
        if (replay.isPresent()) {
            NotificationRedeliveryRecord existing = replay.orElseThrow();
            if (!existing.originalDeliveryId().equals(required.originalDeliveryId())) {
                throw new DomainValidationException(
                        "redeliverNotification.commandId", "is already bound to another delivery");
            }
            // Command receipts deduplicate the write; they never preserve a caller's old access.
            recipientAuthorization.requireActiveRecipient(
                    required.organizationId(),
                    existing.plan().action().parameters().recipientMemberId(),
                    required.actor());
            return existing;
        }
        NotificationPlan original = plans.findByDeliveryId(
                        required.organizationId(), required.originalDeliveryId())
                .orElseThrow(() -> new IllegalArgumentException("Notification delivery was not found"));
        if (original.delivery().version() != required.expectedDeliveryVersion()) {
            throw new IllegalStateException("Notification delivery version conflict");
        }
        if (original.delivery().status() != NotificationDeliveryStatus.FAILED_FINAL) {
            throw new DomainValidationException(
                    "redeliverNotification.delivery", "must be FAILED_FINAL");
        }
        recipientAuthorization.requireActiveRecipient(
                required.organizationId(),
                original.action().parameters().recipientMemberId(),
                required.actor());
        NotificationAuthorizationFacts facts = factsResolver.resolveCurrent(
                original.action().parameters().intentId());
        if (!facts.intent().id().equals(original.action().parameters().intentId())
                || !facts.intent().organizationId().equals(required.organizationId())
                || !facts.intent().recipientMemberId().equals(
                        original.action().parameters().recipientMemberId())) {
            throw new DomainValidationException(
                    "redeliverNotification.authorization",
                    "current intent must match the original organization and recipient");
        }
        templates.requireCurrentPublished(facts.intent().template());
        UtcTimestamp now = timeProvider.now();
        UtcTimestamp notBefore = requirePreference(facts, now);
        NotificationAuthorizationSnapshot snapshot =
                NotificationAuthorizationSnapshot.captureRedelivery(
                        facts, original.delivery().id(), required.commandId());
        NotificationPlan redelivery = plans.findByDeduplicationKey(
                        required.organizationId(), snapshot.deduplicationKey())
                .orElseGet(() -> newPlan(
                        facts, snapshot, notBefore, now, Optional.of(original.delivery().id())));
        return plans.saveRedelivery(new NotificationRedeliveryRecord(
                required.commandId(), original.delivery().id(), redelivery));
    }

    private Optional<NotificationPlan> replaceDriftedPlan(
            NotificationAuthorizationFacts facts,
            NotificationAuthorizationSnapshot snapshot,
            NotificationPlan replacement,
            UtcTimestamp now) {
        return plans.findLatestByIntent(facts.intent().organizationId(), facts.intent().id())
                .filter(previous -> !previous.action().authority().digest().equals(snapshot.digest()))
                .filter(previous -> !previous.delivery().status().terminal())
                .map(previous -> {
                    NotificationInvalidationReason reason = previous.action().authority()
                            .invalidationReason(facts)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Notification digest changed without a drift coordinate"));
                    NotificationPlannedAction invalidatedAction = previous.action().invalidate(
                            previous.action().version(), reason);
                    NotificationReceipt receipt = NotificationReceipt.invalidated(
                            NotificationReceiptId.generate(),
                            previous.delivery(),
                            invalidatedAction,
                            reason,
                            now);
                    NotificationDelivery invalidatedDelivery = previous.delivery().invalidate(
                            previous.delivery().version(), reason, receipt);
                    return plans.replaceDrifted(
                            new NotificationPlan(invalidatedAction, invalidatedDelivery),
                            replacement);
                });
    }

    private NotificationPlan newPlan(
            NotificationAuthorizationFacts facts,
            NotificationAuthorizationSnapshot snapshot,
            UtcTimestamp notBefore,
            UtcTimestamp now,
            Optional<NotificationDeliveryId> redeliveryOf) {
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts, snapshot, notBefore, plus(notBefore, validity), redeliveryOf);
        return new NotificationPlan(action, NotificationDelivery.ready(action, now));
    }

    private static void requireExactIntent(
            NotificationIntent expected, NotificationAuthorizationFacts facts) {
        if (!expected.equals(facts.intent())) {
            throw new DomainValidationException(
                    "notificationAuthorization.intent", "must equal the current server intent");
        }
    }

    private static UtcTimestamp requirePreference(
            NotificationAuthorizationFacts facts, UtcTimestamp now) {
        NotificationPreferenceDecision decision = facts.preference().decide(
                facts.intent().sourceKey().itemType(), now);
        if (decision == NotificationPreferenceDecision.DENIED) {
            throw new DomainValidationException(
                    "notificationPreference", "denies this Inbox item type");
        }
        return decision == NotificationPreferenceDecision.DEFERRED
                ? facts.preference().mutedUntil().orElseThrow()
                : now;
    }

    private static Duration requireValidity(Duration value) {
        Duration required = Objects.requireNonNull(value, "validity");
        if (required.compareTo(Duration.ofMinutes(1)) < 0
                || required.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Notification validity must be between 1 minute and 24 hours");
        }
        return required;
    }

    private static UtcTimestamp plus(UtcTimestamp timestamp, Duration duration) {
        return UtcTimestamp.from(timestamp.value().plus(duration));
    }
}

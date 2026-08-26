package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.collaboration.LarkConnectionAuthorizationResolver;
import io.crewscope.application.notification.NotificationClaim;
import io.crewscope.application.notification.NotificationCredentialHandle;
import io.crewscope.application.notification.NotificationCredentialIssuer;
import io.crewscope.application.notification.NotificationPlan;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Issues one claim-bound Lark credential capability after exact authorization revalidation. */
public final class LarkNotificationCredentialIssuer implements NotificationCredentialIssuer {

    private final LarkConnectionAuthorizationResolver authorizations;
    private final LarkCredentialAccessManager accessManager;
    private final TimeProvider timeProvider;

    public LarkNotificationCredentialIssuer(
            LarkConnectionAuthorizationResolver authorizations,
            LarkCredentialAccessManager accessManager,
            TimeProvider timeProvider) {
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
        this.accessManager = Objects.requireNonNull(accessManager, "accessManager");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public NotificationCredentialHandle issue(
            NotificationPlan plan, NotificationClaim claim, Duration timeToLive) {
        NotificationPlan requiredPlan = Objects.requireNonNull(plan, "plan");
        NotificationClaim requiredClaim = Objects.requireNonNull(claim, "claim");
        if (!requiredPlan.delivery().id().equals(requiredClaim.deliveryId())
                || requiredPlan.delivery().version() != requiredClaim.deliveryVersion()) {
            throw new IllegalArgumentException(
                    "Lark notification credential requires the exact committed claim");
        }
        NotificationAuthorizationSnapshot snapshot = requiredPlan.action().authority();
        LarkConnectionAuthorization authorization = authorizations.resolveCurrent(
                requiredPlan.action().parameters().organizationId(),
                requiredPlan.action().parameters().teamId(),
                snapshot.providerBindingId(),
                LarkCollaborationCapabilities.NOTIFICATION_DELIVERY);
        requireExact(snapshot, authorization);
        UtcTimestamp now = timeProvider.now();
        Duration requested = Objects.requireNonNull(timeToLive, "timeToLive");
        Duration leaseRemaining = Duration.between(
                now.value(), requiredClaim.leaseExpiresAt().value());
        Duration effective = requested.compareTo(leaseRemaining) > 0
                ? leaseRemaining
                : requested;
        if (effective.isZero() || effective.isNegative()
                || effective.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "Lark notification credential TTL must remain inside the active claim");
        }
        PrincipalId workerPrincipal = workerPrincipal(
                authorization.organizationId(), requiredClaim.workerId().value());
        return new LarkNotificationCredentialHandle(
                new LarkApiCallContext(authorization, workerPrincipal),
                accessManager,
                timeProvider,
                UtcTimestamp.from(now.value().plus(effective)));
    }

    private static void requireExact(
            NotificationAuthorizationSnapshot snapshot,
            LarkConnectionAuthorization authorization) {
        if (!snapshot.providerBindingId().equals(authorization.providerBindingId())
                || snapshot.providerBindingVersion() != authorization.providerBindingVersion()
                || !snapshot.connectionId().equals(authorization.connectionId())
                || snapshot.connectionVersion() != authorization.connectionVersion()
                || !snapshot.grantId().equals(authorization.grantId())
                || snapshot.grantVersion() != authorization.grantVersion()) {
            throw LarkProviderException.of(
                    LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                    "Lark notification authorization changed before credential issuance",
                    "LARK_NOTIFICATION_AUTHORIZATION_DRIFT");
        }
    }

    private static PrincipalId workerPrincipal(
            io.crewscope.domain.shared.id.OrganizationId organizationId, String workerId) {
        String canonical = "lark-notification-worker-v1|" + organizationId + '|' + workerId;
        return new PrincipalId(UUID.nameUUIDFromBytes(
                canonical.getBytes(StandardCharsets.UTF_8)));
    }
}

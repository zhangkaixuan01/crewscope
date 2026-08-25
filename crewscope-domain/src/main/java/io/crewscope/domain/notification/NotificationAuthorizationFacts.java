package io.crewscope.domain.notification;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import java.util.Objects;

/** Current server-owned coordinates that must all remain stable until delivery starts. */
public record NotificationAuthorizationFacts(
        NotificationIntent intent,
        NotificationRecipientMappingId recipientMappingId,
        long recipientMappingVersion,
        ProviderBindingId providerBindingId,
        long providerBindingVersion,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        TeamNotificationPolicyId teamPolicyId,
        long teamPolicyVersion,
        NotificationPreference preference) {

    public NotificationAuthorizationFacts {
        intent = Objects.requireNonNull(intent, "intent");
        recipientMappingId = Objects.requireNonNull(recipientMappingId, "recipientMappingId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        grantId = Objects.requireNonNull(grantId, "grantId");
        teamPolicyId = Objects.requireNonNull(teamPolicyId, "teamPolicyId");
        preference = Objects.requireNonNull(preference, "preference");
        if (!preference.memberId().equals(intent.recipientMemberId())) {
            throw new IllegalArgumentException("Notification preference must belong to recipient");
        }
        requireVersion(recipientMappingVersion, "recipientMappingVersion");
        requireVersion(providerBindingVersion, "providerBindingVersion");
        requireVersion(connectionVersion, "connectionVersion");
        requireVersion(grantVersion, "grantVersion");
        requireVersion(teamPolicyVersion, "teamPolicyVersion");
    }

    private static void requireVersion(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

package io.crewscope.application.notification;

import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.notification.NotificationRecipientMappingId;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationDeduplicationKey;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationVariableHash;
import io.crewscope.domain.notification.NotificationVariables;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Closed Provider-neutral command containing stable coordinates and no message body or secret. */
public record NotificationProviderRequest(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId recipientMemberId,
        PlannedActionId actionId,
        ActionDigest actionDigest,
        NotificationTemplateRef template,
        NotificationVariableHash variableHash,
        NotificationRecipientMappingId recipientMappingId,
        ConnectionId connectionId,
        NotificationDeduplicationKey deduplicationKey,
        NotificationAuthorizationSnapshot authorization,
        NotificationVariables variables,
        UUID idempotencyKey,
        int attempt) {

    public NotificationProviderRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        recipientMemberId = Objects.requireNonNull(recipientMemberId, "recipientMemberId");
        actionId = Objects.requireNonNull(actionId, "actionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        template = Objects.requireNonNull(template, "template");
        variableHash = Objects.requireNonNull(variableHash, "variableHash");
        recipientMappingId = Objects.requireNonNull(recipientMappingId, "recipientMappingId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        deduplicationKey = Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        authorization = Objects.requireNonNull(authorization, "authorization");
        variables = Objects.requireNonNull(variables, "variables");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (attempt < 1) {
            throw new IllegalArgumentException("Notification attempt must be positive");
        }
        if (!variableHash.equals(variables.hash())
                || !template.equals(authorization.template())
                || !variableHash.equals(authorization.variableHash())
                || !recipientMappingId.equals(authorization.recipientMappingId())
                || !connectionId.equals(authorization.connectionId())
                || !deduplicationKey.equals(authorization.deduplicationKey())
                || !idempotencyKey.equals(stableIdempotencyKey(
                        organizationId,
                        connectionId,
                        actionId,
                        actionDigest,
                        deduplicationKey))) {
            throw new IllegalArgumentException(
                    "Notification Provider request must bind the exact authorization material");
        }
    }

    /** Derives the only Provider UUID accepted for one exact logical notification action. */
    public static UUID stableIdempotencyKey(
            OrganizationId organizationId,
            ConnectionId connectionId,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            NotificationDeduplicationKey deduplicationKey) {
        String canonical = "notification-provider-v1|"
                + Objects.requireNonNull(organizationId, "organizationId") + '|'
                + Objects.requireNonNull(connectionId, "connectionId") + '|'
                + Objects.requireNonNull(actionId, "actionId") + '|'
                + Objects.requireNonNull(actionDigest, "actionDigest") + '|'
                + Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }
}

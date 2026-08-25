package io.crewscope.domain.notification;

import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Immutable intent produced from an open Inbox source and a validated fixed template. */
public record NotificationIntent(
        NotificationIntentId id,
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId recipientMemberId,
        InboxSourceKey sourceKey,
        ProjectionGeneration projectionGeneration,
        SchemaVersion projectionSchemaVersion,
        NotificationTemplateRef template,
        NotificationVariables variables,
        UtcTimestamp createdAt) {

    public NotificationIntent {
        id = Objects.requireNonNull(id, "id");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        recipientMemberId = Objects.requireNonNull(recipientMemberId, "recipientMemberId");
        sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
        projectionGeneration = Objects.requireNonNull(projectionGeneration, "projectionGeneration");
        projectionSchemaVersion = Objects.requireNonNull(projectionSchemaVersion, "projectionSchemaVersion");
        template = Objects.requireNonNull(template, "template");
        variables = Objects.requireNonNull(variables, "variables");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!organizationId.equals(sourceKey.organizationId())
                || !recipientMemberId.equals(sourceKey.memberId())) {
            throw new DomainValidationException(
                    "notificationIntent.sourceKey", "must match organization and recipient");
        }
    }

    public static NotificationIntent fromOpenInbox(
            InboxItem item,
            NotificationTemplate template,
            java.util.Map<String, String> variables,
            UtcTimestamp createdAt) {
        InboxItem requiredItem = Objects.requireNonNull(item, "item");
        if (!requiredItem.source().isOpen()) {
            throw new DomainValidationException(
                    "notificationIntent.inboxSource", "must be OPEN");
        }
        NotificationTemplate requiredTemplate = Objects.requireNonNull(template, "template");
        return new NotificationIntent(
                NotificationIntentId.fromInboxItem(requiredItem.id()),
                requiredItem.organizationId(),
                requiredItem.teamId(),
                requiredItem.memberId(),
                requiredItem.source().key(),
                requiredItem.projectionGeneration(),
                requiredItem.projectionSchemaVersion(),
                requiredTemplate.ref(),
                requiredTemplate.validateVariables(variables),
                createdAt);
    }
}

package io.crewscope.domain.action;

import io.crewscope.domain.notification.NotificationDeduplicationKey;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationVariableHash;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Closed parameters for a collaboration notification; arbitrary message bodies are absent. */
public record NotifyCollaborationActionParameters(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId recipientMemberId,
        NotificationIntentId intentId,
        NotificationTemplateRef template,
        NotificationVariableHash variableHash,
        NotificationDeduplicationKey deduplicationKey)
        implements ActionParameters {

    public NotifyCollaborationActionParameters {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        recipientMemberId = Objects.requireNonNull(recipientMemberId, "recipientMemberId");
        intentId = Objects.requireNonNull(intentId, "intentId");
        template = Objects.requireNonNull(template, "template");
        variableHash = Objects.requireNonNull(variableHash, "variableHash");
        deduplicationKey = Objects.requireNonNull(deduplicationKey, "deduplicationKey");
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NOTIFY_COLLABORATION;
    }

    @Override
    public void appendCanonical(ActionCanonicalEncoder encoder) {
        Objects.requireNonNull(encoder, "encoder")
                .add(organizationId.toString())
                .add(teamId.toString())
                .add(recipientMemberId.toString())
                .add(intentId.toString())
                .add(template.templateId().toString())
                .add(Long.toString(template.version().value()))
                .add(variableHash.toString())
                .add(deduplicationKey.toString());
    }
}

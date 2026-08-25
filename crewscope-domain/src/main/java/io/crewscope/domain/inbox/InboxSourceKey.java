package io.crewscope.domain.inbox;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.UUID;

/** Stable generation-independent coordinate of one actionable member source revision. */
public record InboxSourceKey(
        OrganizationId organizationId,
        TeamMemberId memberId,
        InboxItemType itemType,
        InboxSourceType sourceType,
        UUID sourceId,
        InboxSourceRevision sourceRevision) {

    public InboxSourceKey {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        itemType = Objects.requireNonNull(itemType, "itemType");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = AggregateId.requireValue(sourceId, "InboxSourceKey.sourceId");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        requireCompatibleType(itemType, sourceType);
    }

    /** Length-prefixing keeps the deterministic identity unambiguous across future encoders. */
    public String canonicalIdentity() {
        return encode(
                organizationId.toString(),
                memberId.toString(),
                itemType.name(),
                sourceType.name(),
                sourceId.toString(),
                Long.toString(sourceRevision.value()));
    }

    private static void requireCompatibleType(
            InboxItemType itemType, InboxSourceType sourceType) {
        boolean compatible = switch (itemType) {
            case OWNERSHIP, EXECUTION -> sourceType == InboxSourceType.RESPONSIBILITY_ASSIGNMENT;
            case REVIEW -> sourceType == InboxSourceType.REVIEW_REQUEST;
            case CONFIRMATION -> sourceType == InboxSourceType.ACTION_CONFIRMATION;
            case EXCEPTION -> sourceType == InboxSourceType.TASK_EXECUTION
                    || sourceType == InboxSourceType.ACTION_DELIVERY
                    || sourceType == InboxSourceType.NOTIFICATION_DELIVERY;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Inbox item type is incompatible with its canonical source type");
        }
    }

    private static String encode(String... values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : values) {
            encoded.append(value.length()).append(':').append(value);
        }
        return encoded.toString();
    }
}

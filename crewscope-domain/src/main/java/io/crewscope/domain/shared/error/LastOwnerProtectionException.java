package io.crewscope.domain.shared.error;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Map;
import java.util.Objects;

/** Reports that a lifecycle command would leave the Team without an effective Owner. */
public final class LastOwnerProtectionException extends DomainException {

    public LastOwnerProtectionException(String teamId, AggregateId memberId) {
        super(new DomainError(
                DomainErrorCode.LAST_OWNER_PROTECTION,
                "Team %s would have no effective Owner after member %s changes"
                        .formatted(
                                requireIdentifier(teamId, "teamId"),
                                Objects.requireNonNull(memberId, "memberId")),
                Map.of(
                        "teamId", requireIdentifier(teamId, "teamId"),
                        "memberId", memberId.toString())));
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}

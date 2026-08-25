package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.collaboration.LarkUnionId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Exact, sanitized result of the fixed open_id member endpoint. */
public record LarkMemberObservation(
        LarkOpenId openId,
        LarkUnionId unionId,
        LarkProviderVersion providerVersion,
        UtcTimestamp observedAt) {

    public LarkMemberObservation {
        openId = Objects.requireNonNull(openId, "openId");
        unionId = Objects.requireNonNull(unionId, "unionId");
        providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    @Override
    public String toString() {
        return "LarkMemberObservation[identity=REDACTED]";
    }
}

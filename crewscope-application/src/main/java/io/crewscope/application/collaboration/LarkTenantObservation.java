package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Exact, sanitized result of the fixed Lark tenant query. */
public record LarkTenantObservation(
        LarkTenantKey tenantKey,
        LarkProviderVersion providerVersion,
        UtcTimestamp observedAt) {

    public LarkTenantObservation {
        tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    @Override
    public String toString() {
        return "LarkTenantObservation[identity=REDACTED]";
    }
}

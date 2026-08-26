package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Current safe coordinates proved by Binding/Connection/Grant and a live tenant query. */
public record LarkConnectionPreflightResult(
        ProviderBindingId providerBindingId,
        long providerBindingVersion,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        UtcTimestamp checkedAt) {

    public LarkConnectionPreflightResult {
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        grantId = Objects.requireNonNull(grantId, "grantId");
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        if (providerBindingVersion < 0 || connectionVersion < 0 || grantVersion < 0) {
            throw new IllegalArgumentException("Lark Preflight versions must not be negative");
        }
    }

    public static LarkConnectionPreflightResult from(
            LarkConnectionAuthorization authorization, UtcTimestamp checkedAt) {
        LarkConnectionAuthorization value = Objects.requireNonNull(
                authorization, "authorization");
        return new LarkConnectionPreflightResult(
                value.providerBindingId(),
                value.providerBindingVersion(),
                value.connectionId(),
                value.connectionVersion(),
                value.grantId(),
                value.grantVersion(),
                checkedAt);
    }
}

package io.crewscope.domain.action;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Stable Connection-scoped external identity and Provider business key. */
public record ExternalResultIdentity(
        ConnectionId connectionId,
        ExternalObjectType objectType,
        String externalId,
        String businessKey) {

    public static final int MAX_VALUE_LENGTH = 500;

    public ExternalResultIdentity {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        objectType = Objects.requireNonNull(objectType, "objectType");
        externalId = requireText(externalId, "externalId");
        businessKey = requireText(businessKey, "businessKey");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.strip().length() > MAX_VALUE_LENGTH) {
            throw new DomainValidationException(
                    "externalResult." + field, "must be non-blank and within the size limit");
        }
        return value.strip();
    }

    public String safeHash() {
        return new ActionCanonicalEncoder("external-result-identity-v1")
                .add(connectionId.toString())
                .add(objectType.name())
                .add(externalId)
                .add(businessKey)
                .digest()
                .toString();
    }
}

package io.crewscope.integration.provider.collaboration;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/**
 * Safe monotonic projection of duplicate Lark message observations.
 *
 * <p>Older observations may arrive after a newer query. They are absorbed only when the exact
 * Provider UUID and message identity agree; conflicting identities fail closed.
 */
public record LarkMessageReceiptProjection(
        LarkNotificationUuid providerReference,
        LarkMessageId messageId,
        UtcTimestamp externalObservedAt) {

    public LarkMessageReceiptProjection {
        providerReference = Objects.requireNonNull(providerReference, "providerReference");
        messageId = Objects.requireNonNull(messageId, "messageId");
        externalObservedAt = Objects.requireNonNull(externalObservedAt, "externalObservedAt");
    }

    public LarkMessageReceiptProjection merge(LarkMessageReceiptProjection candidate) {
        LarkMessageReceiptProjection incoming = Objects.requireNonNull(candidate, "candidate");
        if (!providerReference.equals(incoming.providerReference)
                || !messageId.equals(incoming.messageId)) {
            throw LarkProviderException.of(
                    LarkProviderErrorCode.INVALID_RESPONSE,
                    "Lark duplicate receipt observations conflict",
                    "LARK_RECEIPT_IDENTITY_CONFLICT");
        }
        return incoming.externalObservedAt.compareTo(externalObservedAt) > 0 ? incoming : this;
    }

    @Override
    public String toString() {
        return "LarkMessageReceiptProjection[providerCoordinates=REDACTED, externalObservedAt="
                + externalObservedAt + ']';
    }
}

package io.crewscope.application.observability.sli;

/**
 * Terminal notification-delivery counts inside one window (M12-I03). Recipient-side
 * failures (unavailable channel, revoked authorization) and invalidated or cancelled
 * deliveries are excluded from the delivery-rate denominator — they are addressee or
 * requester states, not platform reliability (ADR-037 §5).
 */
public record SliNotificationStatistics(
        long delivered, long failedFinal, long recipientSideExcluded, long invalidatedOrCancelled) {

    /** The delivery-rate denominator: delivered plus platform-attributable final failures. */
    public long deliveryDenominator() {
        return delivered + failedFinal;
    }
}

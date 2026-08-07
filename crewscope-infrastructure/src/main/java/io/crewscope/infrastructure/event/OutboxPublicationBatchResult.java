package io.crewscope.infrastructure.event;

/** Summary of one polling cycle; stale acknowledgements remain observable as unconfirmed. */
public record OutboxPublicationBatchResult(
        int claimed, int delivered, int failed, int unconfirmed) {

    public static OutboxPublicationBatchResult empty() {
        return new OutboxPublicationBatchResult(0, 0, 0, 0);
    }
}

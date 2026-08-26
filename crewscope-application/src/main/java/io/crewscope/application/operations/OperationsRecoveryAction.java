package io.crewscope.application.operations;

/** Fixed recovery actions allowed by the M6 operations boundary. */
public enum OperationsRecoveryAction {
    REPLAY_OUTBOX_DEAD_LETTER,
    REPLAY_PROJECTION_DEAD_LETTER,
    RETRY_NOTIFICATION_DELIVERY
}

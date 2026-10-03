package io.crewscope.application.retrieval;

/**
 * Lifecycle vocabulary of one durable knowledge-index job (M10-S01 §3.4):
 * QUEUED → CHUNKING → EMBEDDING → ACTIVATING → READY, with FAILED reachable from any
 * live state and CANCELLED only from QUEUED. Repository builds walk every step; a
 * knowledge-entry job inlines its single-chunk activation into the EMBEDDING transaction
 * and jumps EMBEDDING → READY without entering ACTIVATING. Live states are claimable once
 * their lease expires; terminal states never carry a lease.
 */
public enum KnowledgeIndexJobStatus {
    QUEUED,
    CHUNKING,
    EMBEDDING,
    ACTIVATING,
    READY,
    FAILED,
    CANCELLED;

    /** Terminal states never change again and never carry a lease. */
    public boolean terminal() {
        return this == READY || this == FAILED || this == CANCELLED;
    }
}

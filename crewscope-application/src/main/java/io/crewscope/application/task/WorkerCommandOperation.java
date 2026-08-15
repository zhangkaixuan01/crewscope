package io.crewscope.application.task;

/** Closed set of mutations accepted from a Task Token-authenticated Worker. */
public enum WorkerCommandOperation {
    PREPARE,
    START,
    HEARTBEAT,
    PROGRESS,
    COMPLETE,
    FAIL
}
